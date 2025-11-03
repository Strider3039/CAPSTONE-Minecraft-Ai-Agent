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
    """

    def __init__(self, cfg, target=(3.5, -60.0, 32.7), start=(-46, -60.0, -1)):
        super().__init__(cfg)
        self.target = {"x": target[0], "y": target[1], "z": target[2]}
        self.start = start
        self.episode_count = 20
        self.results_file = pathlib.Path(__file__).resolve().parents[3] / "evaluation_results.csv"
        self.reset_episode_vars()

    def reset_episode_vars(self):
        self.prev_pos = None
        self.stuck_ticks = 0
        self.fall_ticks = 0
        self.episode_idx = getattr(self, "episode_idx", 0)
        self.start_time = time.time()
        self.last_pose = None
        self.grounded = False  # track if landed after teleport

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
            x, y, z = self.start
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
        timeout_s = 180.0  # 3-minute limit
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

            y = pose.get("y", 0)
            # Wait until landed after teleport
            if not self.grounded:
                # Wait until player stops falling or is clearly on the ground
                if self.last_pose:
                    dy = y - self.last_pose.get("y", y)
                    # consider grounded if nearly stable for multiple ticks
                    if abs(dy) < 0.01:
                        self.stuck_ticks += 1
                    else:
                        self.stuck_ticks = 0
                    if self.stuck_ticks > 5:  # roughly 5 ticks ≈ 0.4s
                        self.grounded = True
                        print("Player stabilized on ground — starting navigation.")
                self.last_pose = pose
                await asyncio.sleep(0.1)
                continue

            # Normal step processing
            actions = await self.step(obs)
            for act in actions:
                await bridge.send_action(act)

            dx = self.target["x"] - pose.get("x", 0)
            dz = self.target["z"] - pose.get("z", 0)
            dist = math.hypot(dx, dz)

            if dist < self.cfg["runtime"]["policy"]["success_radius"]:
                print(f"Reached goal in {elapsed:.1f}s.")
                return True

            # Falling detection
            if self.last_pose and y < self.last_pose.get("y", 0) - 3.0:
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
            await asyncio.sleep(1.0 / self.cfg["runtime"]["policy"]["tick_hz"])

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
    # Navigation logic (with faster turn & move)
    # ───────────────────────────────────────────────
    async def step(self, obs):
        try:
            payload = obs.get("payload", {})
            pose = payload.get("pose", {})
            raycasts = payload.get("raycasts", [])
            if not pose:
                return []

            px, pz = pose.get("x", 0.0), pose.get("z", 0.0)
            gx, gz = self.target["x"], self.target["z"]
            dx, dz = gx - px, gz - pz
            dist = math.hypot(dx, dz)

            if dist < 1.0:
                return [self._make_action(0.0, 0.0)]

            # Compute target yaw
            yaw_to_goal = math.degrees(math.atan2(-dx, dz))
            current_yaw = pose.get("yaw", 0.0)
            yaw_error = ((yaw_to_goal - current_yaw + 180) % 360) - 180

            yaw_adjust = max(min(yaw_error * 1.2, 15.0), -15.0)

            forward_speed = 1.0

            # Raycast obstacle avoidance
            front = raycasts[2]["dist"] if len(raycasts) >= 3 else 999
            left = raycasts[0]["dist"] if len(raycasts) >= 1 else 999
            right = raycasts[-1]["dist"] if len(raycasts) >= 1 else 999

            if front < 1.5:
                forward_speed = 0.0
                if left > right:
                    yaw_adjust += 8.0
                elif right > left:
                    yaw_adjust -= 8.0

            if abs(yaw_error) > 20:
                forward_speed = 0.0

            # Keep pitch level (no up/down tilt)
            dPitch = 0.0
            return [self._make_action(forward_speed, yaw_adjust)]

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
                "move": {"forward": forward, "strafe": 0.0},
                "look": {"dYaw": dYaw, "dPitch": 0.0},
            },
        }
