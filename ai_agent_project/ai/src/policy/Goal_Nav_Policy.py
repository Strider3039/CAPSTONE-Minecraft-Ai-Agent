# ai/src/policy/goal_nav_policy.py
import math, time
from .base_policy import BasePolicy

class GoalNavPolicy(BasePolicy):
    def __init__(self, cfg, target=(20, 64, 0)):
        super().__init__(cfg)
        self.target = {"x": target[0], "y": target[1], "z": target[2]}
        self.prev_pos = None
        self.stuck_ticks = 0

    async def step(self, obs):
        p = obs["payload"]["pose"]
        rays = obs["payload"].get("rays", [])
        front_clear = obs["payload"].get("front_clear", True)
        collision = obs["payload"].get("collision", {})

        dx, dz = self.target["x"] - p["x"], self.target["z"] - p["z"]
        dist = math.hypot(dx, dz)
        cfg = self.cfg["runtime"]

        if dist < cfg["policy"]["success_radius"]:
            self.log.info("goal reached", extra={"dist": dist})
            return []

        # ---- stuck detection ----
        if self.prev_pos:
            delta = math.hypot(p["x"] - self.prev_pos[0], p["z"] - self.prev_pos[1])
            if delta < cfg["policy"]["stuck_speed_thresh"]:
                self.stuck_ticks += 1
            else:
                self.stuck_ticks = 0
        self.prev_pos = (p["x"], p["z"])

        if self.stuck_ticks > cfg["policy"]["stuck_ticks"]:
            self.log.info("stuck recovery")
            self.stuck_ticks = 0
            return [self._make_action(0.0, 0.0, 15.0)]

        # ---- obstacle avoidance ----
        if (not front_clear) or (
            rays and rays[0]["dist"] < cfg["raycasts"]["front_clear_threshold"]
        ):
            return [self._make_action(0.0, 0.0, 10.0)]

        # ---- yaw controller ----
        desired_yaw = math.degrees(math.atan2(-dx, dz))
        yaw_err = (desired_yaw - p["yaw"] + 180) % 360 - 180
        dYaw = cfg["policy"]["heading"]["yaw_p_gain"] * yaw_err
        dYaw = max(
            -cfg["policy"]["heading"]["max_look_deg"],
            min(cfg["policy"]["heading"]["max_look_deg"], dYaw),
        )

        return [self._make_action(1.0, 0.0, dYaw)]

    def _make_action(self, forward, strafe, dYaw):
        return {
            "proto": "1.1",
            "kind": "action",
            "seq": int(time.time() * 1000),
            "timestamp": time.time(),
            "payload": {
                "move": {"forward": forward, "strafe": strafe},
                "look": {"dYaw": dYaw, "dPitch": 0.0},
            },
        }
