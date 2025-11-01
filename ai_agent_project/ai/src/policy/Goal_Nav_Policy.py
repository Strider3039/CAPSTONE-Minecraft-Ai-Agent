import math, time
from .Base_Policy import BasePolicy

class GoalNavPolicy(BasePolicy):
    def __init__(self, cfg, target=(4, -60, 33)):
        super().__init__(cfg)
        self.target = {"x": target[0], "y": target[1], "z": target[2]}
        self.prev_pos = None
        self.stuck_ticks = 0

    async def step(self, obs):
        payload = obs.get("payload", {})
        pose = payload.get("pose", {})
        rays = payload.get("rays", [])
        front_clear = payload.get("front_clear", True)
        collision = payload.get("collision", {})

        dx, dz = self.target["x"] - pose["x"], self.target["z"] - pose["z"]
        dist = math.hypot(dx, dz)
        policy_cfg = self.cfg["runtime"]["policy"]
        ray_cfg = self.cfg["runtime"]["raycasts"]

        # ---- success check ----
        if dist < policy_cfg["success_radius"]:
            self.log.info("goal reached", extra={"dist": dist})
            return []

        # ---- stuck detection ----
        if self.prev_pos:
            delta = math.hypot(pose["x"] - self.prev_pos[0], pose["z"] - self.prev_pos[1])
            if delta < policy_cfg["stuck_speed_thresh"]:
                self.stuck_ticks += 1
            else:
                self.stuck_ticks = 0
        self.prev_pos = (pose["x"], pose["z"])

        if self.stuck_ticks > policy_cfg["stuck_ticks"]:
            self.log.info("stuck recovery")
            self.stuck_ticks = 0
            # Try jump recovery if stuck
            return [self._make_action(0.0, 0.0, 0.0, jump=True)]

        # ---- obstacle avoidance ----
        blocked = (not front_clear) or (
            rays and rays[0].get("dist", ray_cfg["front_clear_threshold"]) < ray_cfg["front_clear_threshold"]
        )

        if blocked:
            # Jump if grounded and something is just in front
            if collision.get("is_grounded", False):
                return [self._make_action(0.0, 0.0, 0.0, jump=True)]
            else:
                # rotate slightly to search for open path
                return [self._make_action(0.0, 0.0, 10.0)]

        # ---- yaw controller ----
        desired_yaw = math.degrees(math.atan2(-dx, dz))
        yaw_err = (desired_yaw - pose["yaw"] + 180) % 360 - 180
        dYaw = policy_cfg["heading"]["yaw_p_gain"] * yaw_err
        dYaw = max(-policy_cfg["heading"]["max_look_deg"],
                   min(policy_cfg["heading"]["max_look_deg"], dYaw))

        # ---- forward movement ----
        return [self._make_action(1.0, 0.0, dYaw)]

    def _make_action(self, forward, strafe, dYaw, jump=False):
        """Builds a complete action message for the bridge."""
        payload = {
            "move": {"forward": forward, "strafe": strafe},
            "look": {"dYaw": dYaw, "dPitch": 0.0},
        }
        if jump:
            payload["jump"] = True

        return {
            "proto": "1.1",
            "kind": "action",
            "seq": int(time.time() * 1000),
            "timestamp": time.time(),
            "payload": payload,
        }
