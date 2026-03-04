# ai/src/policy/rl/dqn/reward_engine.py

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Dict, Optional
import math


@dataclass
class RewardEngine:
    """
    Reward engine matching your observation schema.

    Observation payload (expected keys):
      - pose: {x, y, z, yaw, pitch}
      - rays: [{hit, distance, angle_deg}, ...]   (your encoder uses 'distance')
      - front_clear: bool
      - world: {time_of_day, weather, biome}
      - inventory: {selected_slot, hotbar: [{id, count}, ...]}
      - collision: {is_grounded, is_colliding, no_progress}

    Rewards (starter shaping):
      - Small survival reward every step
      - Small step penalty to avoid "do nothing" solutions
      - Reward for movement in XZ (exploration)
      - Penalty when 'no_progress' is true (stuck)
      - Small bonus when 'front_clear' is true (open space)
      - Reward for increases in hotbar counts (item pickup)
      - Episode termination based on max steps per episode
    """

    # Tunable weights (recommended starter values)
    survival_reward: float = 0.001
    step_penalty: float = -0.001

    move_scale: float = 1.0
    max_move_reward: float = 0.1

    no_progress_penalty: float = -0.02
    front_clear_bonus: float = 0.005

    item_pickup_reward: float = 0.05

    # Episode bookkeeping
    episode_return: float = 0.0
    steps: int = 0
    max_steps_per_episode: int = 2000  # ~100 seconds at 20 Hz

    # Internal state
    _last_pos: Optional[Dict[str, float]] = field(default=None, init=False)
    _last_hotbar: Optional[list] = field(default=None, init=False)

    # Optional "phase" if you later add curriculum learning
    phase: Optional[str] = None

    # ---------- Public API ----------

    def reset_episode(self) -> None:
        """Called when an episode ends."""
        self.episode_return = 0.0
        self.steps = 0
        self._last_pos = None
        self._last_hotbar = None

    def compute(
        self,
        prev_obs: Optional[dict],
        curr_obs: dict,
    ) -> tuple[float, bool]:
        """
        Core reward function.

        Returns:
            (reward, done)
        """
        reward = 0.0
        done = False

        body_prev = self._extract_body(prev_obs) if prev_obs is not None else None
        body_curr = self._extract_body(curr_obs)

        # 1) Survival reward (tiny) + step penalty (tiny)
        reward += self.survival_reward
        reward += self.step_penalty

        # 2) Movement reward in XZ
        reward += self._movement_reward(body_prev, body_curr)

        # 3) Collision / progress-based signals
        reward += self._collision_reward(body_curr)

        # 4) Item pickup reward (hotbar)
        reward += self._inventory_reward(body_prev, body_curr)

        # Bookkeeping
        self.episode_return += reward
        self.steps += 1

        # 5) Episode termination by step limit
        if self.steps >= self.max_steps_per_episode:
            done = True

        return float(reward), bool(done)

    # ---------- Helpers ----------

    def _extract_body(self, obs: dict | None) -> Dict[str, Any]:
        """
        For your schema, the "body" of the observation is obs["payload"].
        """
        if not obs or not isinstance(obs, dict):
            return {}
        payload = obs.get("payload")
        if isinstance(payload, dict):
            return payload
        return {}

    def _movement_reward(
        self,
        body_prev: Optional[Dict[str, Any]],
        body_curr: Dict[str, Any],
    ) -> float:
        """
        Reward exploration in the XZ plane, based on payload.pose.x/z.
        """
        pos_curr = self._extract_pos(body_curr)
        pos_prev = self._extract_pos(body_prev) if body_prev else self._last_pos

        if pos_curr is None:
            return 0.0

        move_reward = 0.0
        if pos_prev is not None:
            dx = pos_curr["x"] - pos_prev["x"]
            dz = pos_curr["z"] - pos_prev["z"]
            dist = math.sqrt(dx * dx + dz * dz)
            move_reward = min(dist * self.move_scale, self.max_move_reward)

        self._last_pos = pos_curr
        return move_reward

    def _extract_pos(self, body: Optional[Dict[str, Any]]) -> Optional[Dict[str, float]]:
        if not body or not isinstance(body, dict):
            return None

        pose = body.get("pose")
        if not isinstance(pose, dict):
            return None

        try:
            return {
                "x": float(pose.get("x", 0.0)),
                "y": float(pose.get("y", 0.0)),
                "z": float(pose.get("z", 0.0)),
            }
        except Exception:
            return None

    def _collision_reward(self, body_curr: Dict[str, Any]) -> float:
        """
        Use boolean flags from payload.collision and front_clear.
        """
        reward = 0.0

        collision = body_curr.get("collision", {})
        if not isinstance(collision, dict):
            collision = {}

        # Penalty for 'no_progress' – likely stuck against a wall
        if bool(collision.get("no_progress", False)):
            reward += self.no_progress_penalty

        # Bonus if front is clear (encourages moving into open space)
        if bool(body_curr.get("front_clear", False)):
            reward += self.front_clear_bonus

        return reward

    def _inventory_reward(
        self,
        body_prev: Optional[Dict[str, Any]],
        body_curr: Dict[str, Any],
    ) -> float:
        """
        Reward increases in hotbar item counts (pickup/crafting etc.).
        Uses payload.inventory.hotbar entries with {id, count}.
        """
        inv_curr = self._extract_hotbar(body_curr)
        inv_prev = self._extract_hotbar(body_prev) if body_prev else self._last_hotbar

        if inv_curr is None:
            return 0.0

        reward = 0.0

        if inv_prev is not None and len(inv_curr) == len(inv_prev):
            for prev_slot, curr_slot in zip(inv_prev, inv_curr):
                if not isinstance(prev_slot, dict) or not isinstance(curr_slot, dict):
                    continue
                try:
                    prev_count = int(prev_slot.get("count", 0))
                    curr_count = int(curr_slot.get("count", 0))
                except Exception:
                    continue

                if curr_count > prev_count:
                    reward += self.item_pickup_reward

        self._last_hotbar = inv_curr
        return reward

    def _extract_hotbar(self, body: Optional[Dict[str, Any]]) -> Optional[list]:
        if not body or not isinstance(body, dict):
            return None

        inv = body.get("inventory", {})
        if not isinstance(inv, dict):
            return None

        hotbar = inv.get("hotbar")
        if isinstance(hotbar, list):
            return hotbar
        return None