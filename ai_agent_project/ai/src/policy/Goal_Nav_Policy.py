import math, time, csv, asyncio, pathlib, uuid
from .Base_Policy import BasePolicy


class GoalNavPolicy(BasePolicy):
    """
    Smarter goal navigation policy.
    - Teleports safely to start (no midair loop)
    - Waits until stable on ground
    - Faces goal direction before moving
    - Avoids obstacles using raycast distances
    - Re-teleports only if truly stuck or fallen
    - Forces clear daytime environment
    - Smooths look/move outputs (EMA) for buttery motion
    """

    def __init__(self, cfg, target=(89, -60.0, 55), start=(41, -60.0, 21)):
        super().__init__(cfg)
        self.target = {"x": target[0], "y": target[1], "z": target[2]}
        self.start = start
        self.episode_count = 20
        self.results_file = pathlib.Path(__file__).resolve().parents[3] / "evaluation_results_for_corridor.csv"

        # smoothing (EMA); reinitialized each episode as well
        self._alpha_yaw = 0.35   # higher -> snappier turns
        self._alpha_fwd = 0.50   # higher -> faster throttle response
        # --- look controller (PD + slew) ---
        self._yaw_kp = 0.9           # proportional gain
        self._yaw_kd = 0.15          # derivative gain
        self._yaw_slew_deg = 6.0     # max change in dYaw per tick (deg/tick @ 20Hz)
        self._last_dyaw = 0.0
        self._prev_yaw_err = 0.0
        self._last_tick_ts = time.time()


        self.reset_episode_vars()

    def reset_episode_vars(self):
        self.prev_pos = None
        self.stuck_ticks = 0
        self.fall_ticks = 0
        self.episode_idx = getattr(self, "episode_idx", 0)
        self.start_time = time.time()
        self.last_pose = None
        self.grounded = False  # track if landed after teleport

        # transient state for smoothing & control
        self._yaw_ema = 0.0
        self._fwd_ema = 0.0
        self._commit_turn_ticks = 0  # short commitment to turning to avoid twitch
        self._last_dyaw = 0.0
        self._prev_yaw_err = 0.0
        self._last_tick_ts = time.time()


        # navigation persistent state
        self.mode = "move"        # move / turn / escape
        self.turn_dir = 1         # +1 cw, -1 ccw
        self._last_pos = None
        self._stuck_counter = 0
        self._escape_progress = 0.0
        self._escape_start = (0.0, 0.0)

    # ───────────────────────────────────────────────
    # Evaluation loop
    # ───────────────────────────────────────────────
    async def evaluate(self, bridge):
        print(f"Starting evaluation for {self.episode_count} episodes...")
        results = []

        for i in range(self.episode_count):
            self.episode_idx = i + 1
            print(f"\nEpisode {self.episode_idx}/{self.episode_count}")

            # --- Safe teleport + environment reset ---
            await self.send_command("time set day")
            await asyncio.sleep(0.2)
            await self.send_command("weather clear")
            await asyncio.sleep(0.2)

            # Teleport slightly above start Y to avoid clipping into floor
            await self.send_command(f"tp @p {self.start[0]:.2f} {self.start[1] + 0.5:.2f} {self.start[2]:.2f}")
            await asyncio.sleep(1.0)

            self.reset_episode_vars()
            success = await self.run_single_episode(self)
            duration = time.time() - self.start_time

            result_text = "PASS" if success else "FAIL"
            print(f"Episode {self.episode_idx} {result_text} — {duration:.1f}s")

            results.append({
                "episode": self.episode_idx,
                "result": result_text,
                "duration_s": f"{duration:.1f}"
            })

            # cooldown to prevent mid-air spawn reuse
            await asyncio.sleep(2.0)

        self.save_results(results)
        passes = sum(1 for r in results if r["result"] == "PASS")
        print(f"\nEvaluation complete: {passes}/{self.episode_count} passes ({100*passes/self.episode_count:.1f}%)")

    # ───────────────────────────────────────────────
    # Run one episode
    # ───────────────────────────────────────────────
    async def run_single_episode(self, bridge):
        timeout_s = 90.0  # 3-minute limit
        start_time = time.time()

        while True:
            elapsed = time.time() - start_time
            if elapsed > timeout_s:
                print(f"Timed out after {timeout_s:.1f}s, teleporting back.")
                await self.send_command(f"tp @p {self.start[0]:.2f} {self.start[1] + 0.5:.2f} {self.start[2]:.2f}")
                await asyncio.sleep(2.0)
                return False

            obs = await bridge.get_observation()
            if not obs:
                await asyncio.sleep(0.05)
                continue

            payload = obs.get("payload", {})
            pose = payload.get("pose", {})
            if not pose:
                continue

            y = pose.get("y", 0.0)
            # Wait until landed after teleport: watch for vertical stability
            if not self.grounded:
                if self.last_pose:
                    dy = y - self.last_pose.get("y", y)
                    if abs(dy) < 0.01:
                        self.stuck_ticks += 1
                    else:
                        self.stuck_ticks = 0
                    if self.stuck_ticks > 5:  # ~0.25s at 20 Hz
                        self.grounded = True
                        print("Player stabilized on ground — starting navigation.")
                self.last_pose = pose
                await asyncio.sleep(0.1)
                continue

            # Normal step processing
            actions = await self.step(obs)
            for act in actions:
                await bridge.send_action(act)

            dx = self.target["x"] - pose.get("x", 0.0)
            dz = self.target["z"] - pose.get("z", 0.0)
            dist = math.hypot(dx, dz)

            if dist < self.cfg["runtime"]["policy"]["success_radius"]:
                print(f"Reached goal in {elapsed:.1f}s.")
                return True

            # Falling detection
            if self.last_pose and y < self.last_pose.get("y", 0.0) - 3.0:
                self.fall_ticks += 1
            else:
                self.fall_ticks = 0

            if self.fall_ticks > 8:
                print("Detected fall — resetting to start.")
                await self.send_command(f"tp @p {self.start[0]:.2f} {self.start[1] + 0.5:.2f} {self.start[2]:.2f}")
                await asyncio.sleep(2.0)
                self.fall_ticks = 0
                self.grounded = False
                continue

            self.last_pose = pose

            # run policy at least at 20 Hz to match server/game cadence
            tick_hz = max(20.0, float(self.cfg["runtime"]["policy"].get("tick_hz", 20.0)))
            await asyncio.sleep(1.0 / tick_hz)

    # ───────────────────────────────────────────────
    # Save results
    # ───────────────────────────────────────────────
    def save_results(self, results):
        with open(self.results_file, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=["episode", "result", "duration_s"])
            writer.writeheader()
            writer.writerows(results)
        print(f"Results written to {self.results_file}")

    # ───────────────────────────────────────────────
    # Bridge helpers
    # ───────────────────────────────────────────────
    async def get_observation(self):
        if hasattr(self, "_latest_obs") and self._latest_obs:
            obs = self._latest_obs
            self._latest_obs = None
            return obs
        await asyncio.sleep(0.05)
        return None

    async def send_action(self, act):
        if hasattr(self, "_action_queue"):
            await self._action_queue.put(act)

    # ───────────────────────────────────────────────
    # Navigation logic (with smoother turn & move)
    # ───────────────────────────────────────────────
    async def step(self, obs):
        """
        Adaptive goal navigation with dynamic escape direction.
        - Turns toward the side with more free space when stuck.
        - Moves toward goal when clear.
        - Short turn-commit window to avoid twitch.
        - EMA smoothing on yaw and throttle to remove micro-stutter.
        """
        try:
            payload = obs.get("payload", {})
            pose = payload.get("pose", {})
            rays = payload.get("rays", [])
            if not pose or not rays:
                return []

            # --- pose and goal ---
            px, pz = pose.get("x", 0.0), pose.get("z", 0.0)
            gx, gz = self.target["x"], self.target["z"]
            dx, dz = gx - px, gz - pz
            dist_to_goal = math.hypot(dx, dz)
            if dist_to_goal < 1.0:
                return [self._make_action(0.0, 0.0)]

            yaw_to_goal = math.degrees(math.atan2(-dx, dz))
            current_yaw = pose.get("yaw", 0.0)

            # --- init persistent state ---
            if self._last_pos is None:
                self._last_pos = (px, pz)

            # --- rays ---
            n = max(1, len(rays))
            ray_angles = [r.get("angle_deg", i * (360.0 / n)) for i, r in enumerate(rays)]
            ray_dists = [max(0.0, r.get("dist", 0.0)) for r in rays]

            def ang_diff(a, b):
                return ((a - b + 180) % 360) - 180

            # front clearance (±20°)
            front_bins = [a for a in ray_angles if abs(ang_diff(a, current_yaw)) <= 20]
            front_cnt = max(1, len(front_bins))
            front_clear = sum(
                d for a, d in zip(ray_angles, ray_dists)
                if abs(ang_diff(a, current_yaw)) <= 20
            ) / front_cnt

            # ───────────── Escape Mode ─────────────
            if self.mode == "escape":
                dist_moved = math.hypot(px - self._escape_start[0], pz - self._escape_start[1])
                if dist_moved > 1.0:
                    print("[POLICY] Escape complete — resuming goal seeking.")
                    self.mode = "move"
                else:
                    # steady escape posture (server will hold at 20 Hz)
                    return [self._make_action(0.35, 22.0 * self.turn_dir)]

            # ───────────── Normal Movement (full-speed mapping) ─────────────
            forward_speed = 0.0
            dYaw = 0.0

            # angular error to goal
            yaw_err = ang_diff(yaw_to_goal, current_yaw)

            # Decide if front is too tight to proceed straight
            too_close_front = (front_clear < 0.5)

            if too_close_front:
                # turn, but keep a bit of forward to maintain momentum
                self.mode = "turn"
                dYaw = 30.0 * self.turn_dir
                forward_speed = 0.25    # was 0.0 -> keep moving
                self._commit_turn_ticks = max(self._commit_turn_ticks, 6)  # ~0.3s
            else:
                # clear — go toward goal
                self.mode = "move"

                # Map yaw error to forward speed: full speed when nearly aligned,
                # taper when badly misaligned to avoid wide arcs.
                abs_err = abs(yaw_err)
                if abs_err < 8.0:
                    forward_speed = 1.0
                elif abs_err < 20.0:
                    forward_speed = 0.8
                elif abs_err < 30.0:
                    forward_speed = 0.55
                else:
                    forward_speed = 0.35  # still move; server holds it at 20 Hz

                # Turn proportionally toward goal, capped
                dYaw = max(min(yaw_err, 30.0), -30.0)

            # honor turn-commit window to avoid flip-flop (keep some forward!)
            if self._commit_turn_ticks > 0:
                self._commit_turn_ticks -= 1
                self.mode = "turn"
                dYaw = 25.0 * self.turn_dir
                # keep a little forward push during the commit
                forward_speed = max(forward_speed, 0.25)

            # ───────────── Stuck Detection ─────────────
            last_px, last_pz = self._last_pos
            moved = math.hypot(px - last_px, pz - last_pz)
            self._last_pos = (px, pz)

            if moved < 0.05:
                self._stuck_counter += 1
            else:
                self._stuck_counter = 0

            if self._stuck_counter > 15:
                # Decide escape direction dynamically
                left_bins = [a for a in ray_angles if 40 <= ang_diff(a, current_yaw) <= 100]
                right_bins = [a for a in ray_angles if -100 <= ang_diff(a, current_yaw) <= -40]
                left_cnt = max(1, len(left_bins))
                right_cnt = max(1, len(right_bins))

                left_clear = sum(
                    d for a, d in zip(ray_angles, ray_dists)
                    if 40 <= ang_diff(a, current_yaw) <= 100
                ) / left_cnt

                right_clear = sum(
                    d for a, d in zip(ray_angles, ray_dists)
                    if -100 <= ang_diff(a, current_yaw) <= -40
                ) / right_cnt

                self.turn_dir = 1 if right_clear >= left_clear else -1
                side = "right" if self.turn_dir == 1 else "left"
                print(f"[POLICY] Stuck detected — escaping to {side} (R={right_clear:.2f}, L={left_clear:.2f})")

                self._stuck_counter = 0
                self.mode = "escape"
                self._escape_start = (px, pz)

                # single steady escape command (server repeats it at 20 Hz)
                return [self._make_action(-0.15, 35.0 * self.turn_dir)]

            # --- Smooth outputs (EMA) to remove micro-stutter ---
            dYaw = max(min(dYaw, 30.0), -30.0)
            forward_speed = max(min(forward_speed, 1.0), -1.0)

            # --- PD controller for yaw rate (deg/tick) with slew-rate limiting ---
            # error: desired heading - current heading
            yaw_err = dYaw  # reuse your computed "needed turn" in degrees as the error proxy
            now = time.time()
            # Estimate tick dt from policy loop rate (fallback to 1/20 s)
            dt = max(1e-3, min(0.2, now - self._last_tick_ts))
            self._last_tick_ts = now

            derr = (yaw_err - self._prev_yaw_err) / dt
            self._prev_yaw_err = yaw_err

            # raw command (deg per tick equivalent)
            dyaw_cmd = self._yaw_kp * yaw_err + self._yaw_kd * derr
            # cap absolute yaw rate
            dyaw_cmd = max(min(dyaw_cmd, 30.0), -30.0)

            # slew-rate limit: don't change dYaw faster than self._yaw_slew_deg per tick
            delta = dyaw_cmd - self._last_dyaw
            max_step = self._yaw_slew_deg
            if delta > max_step:
                dyaw_cmd = self._last_dyaw + max_step
            elif delta < -max_step:
                dyaw_cmd = self._last_dyaw - max_step
            self._last_dyaw = dyaw_cmd

            # --- Forward EMA (keeps throttle smooth)
            self._fwd_ema = (1.0 - self._alpha_fwd) * self._fwd_ema + self._alpha_fwd * forward_speed

            # deadzones: keep tiny noise away, but don't kill small forward during turn
            yaw_out = 0.0 if abs(dyaw_cmd) < 0.25 else dyaw_cmd
            fwd_out = 0.0 if abs(self._fwd_ema) < 0.01 else self._fwd_ema

            return [self._make_action(fwd_out, yaw_out)]

        except Exception as e:
            print("[POLICY ERROR] step:", e)
            return []

    # ───────────────────────────────────────────────
    # Action builder
    # ───────────────────────────────────────────────
    def _make_action(self, forward, dYaw):
        return {
            "proto": "1",
            "kind": "action",
            "seq": 0,
            "timestamp": time.time(),
            "action_id": str(uuid.uuid4()),
            "payload": {
                "move": {"forward": float(forward), "strafe": 0.0},
                "look": {"dYaw": float(dYaw), "dPitch": 0.0},
            },
        }
