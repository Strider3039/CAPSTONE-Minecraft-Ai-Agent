from __future__ import annotations
from typing import Optional, Dict, Any, Tuple
import math

class RewardEngine:
    """
    Reward system matching your observation schema.
    Provides:
      - survival reward
      - movement reward (XZ)
      - no_progress penalty
      - front_clear bonus
      - item pickup reward
    """

    def __init__(self):
        self.episode_return = 0.0
        self._prev_hotbar = {}

    def compute(self, prev_obs: Optional[Dict[str, Any]], curr_obs: Dict[str, Any]) -> Tuple[float, bool]:
        try:
            curr = curr_obs["payload"]
        except Exception:
            return 0.0, False

        prev = None
        if prev_obs:
            try: prev = prev_obs["payload"]
            except: prev = None

        reward = 0.0
        done = False

        # 1. Survival reward
        reward += 0.01

        # 2. Movement reward (in XZ)
        if prev is not None:
            ppos = prev.get("pose", {})
            cpos = curr.get("pose", {})
            px, pz = ppos.get("x"), ppos.get("z")
            x, z = cpos.get("x"), cpos.get("z")
            if None not in (px, pz, x, z):
                dist = math.sqrt((x-px)**2 + (z-pz)**2)
                reward += min(dist, 0.1)

        # 3. no_progress penalty
        if curr.get("collision", {}).get("no_progress", False):
            reward -= 0.02

        # 4. front_clear bonus
        if curr.get("front_clear", False):
            reward += 0.005

        # 5. Item pickup reward
        inv = curr.get("inventory", {})
        hotbar = inv.get("hotbar", [])

        curr_map = {}
        for slot in hotbar:
            item = slot.get("id")
            count = slot.get("count", 0)
            if item:
                curr_map[item] = curr_map.get(item, 0) + int(count)

        if prev is not None:
            for item, cnt in curr_map.items():
                prev_cnt = self._prev_hotbar.get(item, 0)
                if cnt > prev_cnt:
                    reward += 0.05

        self._prev_hotbar = curr_map
        self.episode_return += reward
        return reward, done

    def reset_episode(self):
        self.episode_return = 0.0
        self._prev_hotbar = {}
