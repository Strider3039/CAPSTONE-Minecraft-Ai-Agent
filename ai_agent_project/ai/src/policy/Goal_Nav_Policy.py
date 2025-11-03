import math, time, csv, asyncio, pathlib
from .Base_Policy import BasePolicy

class GoalNavPolicy(BasePolicy):
    def __init__(self, cfg, target=(4, -60, 33), start=(-44, 60, -2)):
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
        print(f"🚀 Starting evaluation for {self.episode_count} episodes...")
        results = []

        for i in range(self.episode_count):
            self.episode_idx = i + 1
            print(f"\n▶ Episode {self.episode_idx}/{self.episode_count}")
            await self.send_command(f"tp @p {self.start[0]:.2f} {self.start[1]:.2f} {self.start[2]:.2f}")
            await asyncio.sleep(2.0)

            self.reset_episode_vars()
            success = await self.run_single_episode(bridge)
            duration = time.time() - self.start_time
            results.append({
                "episode": self.episode_idx,
                "result": "PASS" if success else "FAIL",
                "duration_s": f"{duration:.1f}"
            })
            print(f"🏁 Episode {self.episode_idx} {'PASS' if success else 'FAIL'} — {duration:.1f}s")
            await asyncio.sleep(2.0)

        self.save_results(results)
        print("\n✅ Evaluation complete.")
        passes = sum(1 for r in results if r["result"] == "PASS")
        print(f"Total: {passes}/{self.episode_count} passes ({100*passes/self.episode_count:.1f}%)")

    async def run_single_episode(self, bridge):
        timeout_ticks = 2000
        for _ in range(timeout_ticks):
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
                return True

            await asyncio.sleep(1.0 / self.cfg["runtime"]["policy"]["tick_hz"])
        return False

    def save_results(self, results):
        with open(self.results_file, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=["episode", "result", "duration_s"])
            writer.writeheader()
            writer.writerows(results)
        print(f"📝 Results written to {self.results_file}")

    # unchanged step() and _make_action() ...
