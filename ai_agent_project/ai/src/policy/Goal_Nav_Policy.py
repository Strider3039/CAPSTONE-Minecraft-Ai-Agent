import math, time, csv, asyncio, pathlib
from .Base_Policy import BasePolicy

class GoalNavPolicy(BasePolicy):
    def __init__(self, cfg, target=(10, 64, 10), start=(0, 64, 0)):
        super().__init__(cfg)
        self.target = {"x": target[0], "y": target[1], "z": target[2]}
        self.start = start
        self.episode_count = 20
        self.results_file = pathlib.Path(__file__).resolve().parents[3] / "evaluation_results.csv"
        self.reset_episode_vars()

    def reset_episode_vars(self):
        self.prev_pos = None
        self.stuck_ticks = 0
        self.state = "navigate"
        self.pause_until = 0.0
        self.last_action = None
        self.last_update = 0.0
        self.episode_idx = getattr(self, "episode_idx", 0)
        self.start_time = time.time()

    async def evaluate(self, bridge):
        """Main evaluation harness loop."""
        print(f"🚀 Starting evaluation for {self.episode_count} episodes...")
        results = []

        for i in range(self.episode_count):
            self.episode_idx = i + 1
            print(f"\n▶ Episode {self.episode_idx}/{self.episode_count}")
            await self.teleport_player(bridge, *self.start)
            self.reset_episode_vars()
            success = await self.run_single_episode(bridge)
            duration = time.time() - self.start_time
            results.append({
                "episode": self.episode_idx,
                "result": "PASS" if success else "FAIL",
                "duration_s": f"{duration:.1f}"
            })
            print(f"🏁 Episode {self.episode_idx} {'PASS' if success else 'FAIL'} — {duration:.1f}s")
            await asyncio.sleep(2.0)  # small delay before restart

        self.save_results(results)
        print("\n✅ Evaluation complete.")
        passes = sum(1 for r in results if r["result"] == "PASS")
        print(f"Total: {passes}/{self.episode_count} passes ({100*passes/self.episode_count:.1f}%)")

    async def run_single_episode(self, bridge):
        """Runs one complete episode loop until goal reached or timeout."""
        timeout_ticks = 2000
        for t in range(timeout_ticks):
            obs = await bridge.get_observation()
            if not obs:
                await asyncio.sleep(0.05)
                continue

            actions = await self.step(obs)
            for act in actions:
                await bridge.send_action(act)

            payload = obs.get("payload", {})
            pose = payload.get("pose", {})
            dx, dz = self.target["x"] - pose["x"], self.target["z"] - pose["z"]
            dist = math.hypot(dx, dz)

            if dist < self.cfg["runtime"]["policy"]["success_radius"]:
                return True  # success

            await asyncio.sleep(1.0 / self.cfg["runtime"]["policy"]["tick_hz"])
        return False

    async def teleport_player(self, bridge, x, y, z):
        """Teleport player to starting position using the bridge."""
        print(f"Teleporting player to {x}, {y}, {z}")
        await bridge.send_command(f"/tp @p {x:.2f} {y:.2f} {z:.2f}")
        await asyncio.sleep(1.0)

    def save_results(self, results):
        """Write evaluation results to CSV."""
        with open(self.results_file, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=["episode", "result", "duration_s"])
            writer.writeheader()
            writer.writerows(results)
        print(f"📝 Results written to {self.results_file}")

    async def step(self, obs):
        """Policy control logic — smooth and smarter navigation."""
        now = time.time()
        if now - self.last_update < 0.1:
            return [self.last_action] if self.last_action else []

        payload = obs.get("payload", {})
        pose = payload.get("pose", {})
        rays = payload.get("rays", [])
        front_clear = payload.get("front_clear", True)
        collision = payload.get("collision", {})

        dx, dz = self.target["x"] - pose["x"], self.target["z"] - pose["z"]
        dist = math.hypot(dx, dz)
        policy_cfg = self.cfg["runtime"]["policy"]
        ray_cfg = self.cfg["runtime"]["raycasts"]

        # ---- goal reached ----
        if dist < policy_cfg["success_radius"]:
            return []

        # ---- pause handling ----
        if self.state == "pause":
            if now < self.pause_until:
                act = self._make_action(0.0, 0.0, 0.0)
                self.last_action, self.last_update = act, now
                return [act]
            else:
                self.state = "navigate"

        # ---- obstacle detection ----
        blocked = (not front_clear) or (
            rays and rays[0].get("dist", ray_cfg["front_clear_threshold"]) <
            ray_cfg["front_clear_threshold"]
        )

        # analyze left/right clearance
        left_dist = sum(r.get("dist", 0) for r in rays[len(rays)//2:]) / max(1, len(rays)//2)
        right_dist = sum(r.get("dist", 0) for r in rays[:len(rays)//2]) / max(1, len(rays)//2)
        best_turn_dir = -1.0 if left_dist > right_dist else 1.0

        desired_yaw = math.degrees(math.atan2(-dx, dz))
        yaw_err = (desired_yaw - pose["yaw"] + 180) % 360 - 180

        if blocked:
            self.state = "pause"
            self.pause_until = now + 0.5
            turn_speed = policy_cfg["heading"]["max_look_deg"] * 0.8 * best_turn_dir
            act = self._make_action(0.0, 0.0, turn_speed)
        else:
            dYaw = policy_cfg["heading"]["yaw_p_gain"] * yaw_err
            dYaw = max(-policy_cfg["heading"]["max_look_deg"],
                       min(policy_cfg["heading"]["max_look_deg"], dYaw))
            forward_speed = 1.0 if abs(yaw_err) < 45 else 0.6
            act = self._make_action(forward_speed, 0.0, dYaw)

        self.last_action, self.last_update = act, now
        return [act]

    def _make_action(self, forward=0.0, strafe=0.0, dYaw=0.0):
        """Create schema-compliant, jump-free action."""
        return {
            "proto": "1",
            "kind": "action",
            "seq": int(time.time() * 1000),
            "timestamp": time.time(),
            "action_id": f"act_{int(time.time() * 1000)}",
            "payload": {
                "move": {"forward": forward, "strafe": strafe},
                "look": {"dYaw": dYaw, "dPitch": 0.0}
            }
        }
