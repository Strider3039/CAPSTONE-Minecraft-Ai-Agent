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

    def __init__(self, cfg, target=(29.3, -60.0, 124.5), start=(-19, -60.0, 90)):
        super().__init__(cfg)
        self.target = {"x": target[0], "y": target[1], "z": target[2]}
        self.start = start
        self.episode_count = 20
        self.results_file = pathlib.Path(__file__).resolve().parents[3] / "evaluation_results_for_corridor.csv"

        # smoothing (EMA)
        self._alpha_yaw = 0.35
        self._alpha_fwd = 0.50

        # look controller (PD + slew)
        self._yaw_kp = 0.9
        self._yaw_kd = 0.15
        self._yaw_slew_deg = 6.0
        self._last_dyaw = 0.0
        self._prev_yaw_err = 0.0
        self._last_tick_ts = time.time()

        self.reset_episode_vars()

    def reset_episode_vars(self):
        """Reset transient and navigation state for a new episode."""
        self.prev_pos = None
        self.stuck_ticks = 0
        self.fall_ticks = 0
        self.episode_idx = getattr(self, "episode_idx", 0)
        self.start_time = time.time()
        self.last_pose = None
        self.grounded = False

        # control smoothing
        self._yaw_ema = 0.0
        self._fwd_ema = 0.0
        self._commit_turn_ticks = 0
        self._last_dyaw = 0.0
        self._prev_yaw_err = 0.0
        self._last_tick_ts = time.time()

        # navigation mode
        self.mode = "move"
        self.turn_dir = 1
        self._last_pos = None
        self._stuck_counter = 0
        self._escape_progress = 0.0
        self._escape_start = (0.0, 0.0)

        # teleport cooldown (to prevent instant re-trigger)
        self._teleport_cooldown = 0

    # ───────────────────────────────────────────────
    # Evaluation loop
    # ───────────────────────────────────────────────
    async def evaluate(self, bridge):
        print(f"Starting evaluation for {self.episode_count} episodes...")
        results = []

        for i in range(self.episode_count):
            self.episode_idx = i + 1
            print(f"\nEpisode {self.episode_idx}/{self.episode_count}")

            # Safe environment reset
            await self.send_command("time set day")
            await asyncio.sleep(0.2)
            await self.send_command("weather clear")
            await asyncio.sleep(0.2)

            # Teleport slightly above start Y
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

            await asyncio.sleep(2.0)

        self.save_results(results)
        passes = sum(1 for r in results if r["result"] == "PASS")
        print(f"\nEvaluation complete: {passes}/{self.episode_count} passes ({100*passes/self.episode_count:.1f}%)")

    # ───────────────────────────────────────────────
    # Run one episode
    # ───────────────────────────────────────────────
    async def run_single_episode(self, bridge):
        timeout_s = 90.0
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

            # Wait until landed after teleport
            if not self.grounded:
                if self.last_pose:
                    dy = y - self.last_pose.get("y", y)
                    if abs(dy) < 0.01:
                        self.stuck_ticks += 1
                    else:
                        self.stuck_ticks = 0
                    if self.stuck_ticks > 5:
                        self.grounded = True
                        print("Player stabilized on ground — starting navigation.")
                self.last_pose = pose
                await asyncio.sleep(0.1)
                continue

            # Normal step
            actions = await self.step(obs)
            for act in actions:
                await bridge.send_action(act)

            dx = self.target["x"] - pose.get("x", 0.0)
            dz = self.target["z"] - pose.get("z", 0.0)
            dist = math.hypot(dx, dz)

            if dist < self.cfg["runtime"]["policy"]["success_radius"]:
                print(f"Reached goal in {elapsed:.1f}s.")
                return True

            # Fall detection
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
            tick_hz = max(20.0, float(self.cfg["runtime"]["policy"].get("tick_hz", 20.0)))
            await asyncio.sleep(1.0 / tick_hz)

    # ───────────────────────────────────────────────
    # Navigation logic (with smoother turn & move)
    # ───────────────────────────────────────────────
    async def step(self, obs):
        try:
            payload = obs.get("payload", {})
            pose = payload.get("pose", {})
            rays = payload.get("rays", [])
            if not pose or not rays:
                return []

            # pose and goal
            px, py, pz = pose.get("x", 0.0), pose.get("y", 0.0), pose.get("z", 0.0)
            gx, gy, gz = self.target["x"], self.target["y"], self.target["z"]
            dx, dz = gx - px, gz - pz
            dist_to_goal = math.hypot(dx, dz)
            dy = abs(gy - py)

            # skip during teleport cooldown
            if self._teleport_cooldown > 0:
                self._teleport_cooldown -= 1
                return []

            # teleport to start when goal reached
            success_radius = float(self.cfg["runtime"]["policy"].get("success_radius", 2.0))
            if dist_to_goal < success_radius and dy < 2.0:
                print(f"[POLICY] Reached goal at ({px:.2f},{pz:.2f}) → teleporting to start.")
                await self.send_command(f"tp @p {self.start[0]:.2f} {self.start[1] + 0.5:.2f} {self.start[2]:.2f}")
                await asyncio.sleep(2.0)
                self.reset_episode_vars()
                self._teleport_cooldown = 40  # 2s cooldown (20Hz)
                return []

            # continue normal movement logic
            yaw_to_goal = math.degrees(math.atan2(-dx, dz))
            current_yaw = pose.get("yaw", 0.0)

            if self._last_pos is None:
                self._last_pos = (px, pz)

            n = max(1, len(rays))
            ray_angles = [r.get("angle_deg", i * (360.0 / n)) for i, r in enumerate(rays)]
            ray_dists = [max(0.0, r.get("dist", 0.0)) for r in rays]

            def ang_diff(a, b):
                return ((a - b + 180) % 360) - 180

            front_clear = sum(
                d for a, d in zip(ray_angles, ray_dists)
                if abs(ang_diff(a, current_yaw)) <= 20
            ) / max(1, len([a for a in ray_angles if abs(ang_diff(a, current_yaw)) <= 20]))

            # escape mode
            if self.mode == "escape":
                dist_moved = math.hypot(px - self._escape_start[0], pz - self._escape_start[1])
                if dist_moved > 1.0:
                    print("[POLICY] Escape complete — resuming goal seeking.")
                    self.mode = "move"
                else:
                    return [self._make_action(0.35, 22.0 * self.turn_dir)]

            forward_speed = 0.0
            dYaw = 0.0
            yaw_err = ang_diff(yaw_to_goal, current_yaw)
            too_close_front = (front_clear < 0.5)

            if too_close_front:
                self.mode = "turn"
                dYaw = 30.0 * self.turn_dir
                forward_speed = 0.25
                self._commit_turn_ticks = max(self._commit_turn_ticks, 6)
            else:
                self.mode = "move"
                abs_err = abs(yaw_err)
                if abs_err < 8.0:
                    forward_speed = 1.0
                elif abs_err < 20.0:
                    forward_speed = 0.8
                elif abs_err < 30.0:
                    forward_speed = 0.55
                else:
                    forward_speed = 0.35
                dYaw = max(min(yaw_err, 30.0), -30.0)

            if self._commit_turn_ticks > 0:
                self._commit_turn_ticks -= 1
                self.mode = "turn"
                dYaw = 25.0 * self.turn_dir
                forward_speed = max(forward_speed, 0.25)

            last_px, last_pz = self._last_pos
            moved = math.hypot(px - last_px, pz - last_pz)
            self._last_pos = (px, pz)

            if moved < 0.05:
                self._stuck_counter += 1
            else:
                self._stuck_counter = 0

            if self._stuck_counter > 15:
                print("[POLICY] Stuck detected — initiating escape.")
                self.turn_dir *= -1
                self._stuck_counter = 0
                self.mode = "escape"
                self._escape_start = (px, pz)
                return [self._make_action(-0.15, 35.0 * self.turn_dir)]

            # yaw smoothing & throttle smoothing
            now = time.time()
            dt = max(1e-3, min(0.2, now - self._last_tick_ts))
            self._last_tick_ts = now

            derr = (yaw_err - self._prev_yaw_err) / dt
            self._prev_yaw_err = yaw_err

            dyaw_cmd = self._yaw_kp * yaw_err + self._yaw_kd * derr
            dyaw_cmd = max(min(dyaw_cmd, 30.0), -30.0)

            delta = dyaw_cmd - self._last_dyaw
            max_step = self._yaw_slew_deg
            if delta > max_step:
                dyaw_cmd = self._last_dyaw + max_step
            elif delta < -max_step:
                dyaw_cmd = self._last_dyaw - max_step
            self._last_dyaw = dyaw_cmd

            self._fwd_ema = (1.0 - self._alpha_fwd) * self._fwd_ema + self._alpha_fwd * forward_speed
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
