# ai/src/policy/rl/dqn/reward_engine.py

from __future__ import annotations
from typing import Optional, Dict, Any, Tuple
import math


class RewardEngine:
    """
    Reward shaping for Minecraft observations.

    Input:
      - prev_obs: previous *observation message* (or None)
      - curr_obs: current observation message (full WS payload)

    Returns:
      (reward, done)
    """

    def compute(
        self,
        prev_obs: Optional[Dict[str, Any]],
        curr_obs: Dict[str, Any],
    ) -> Tuple[float, bool]:
        try:
            curr_o = curr_obs["payload"]["observation"]
        except Exception:
            # If schema isn’t what we expect, just give 0 reward and continue
            return 0.0, False

        prev_o = None
        if prev_obs is not None:
            try:
                prev_o = prev_obs["payload"]["observation"]
            except Exception:
                prev_o = None

        reward = 0.0
        done = False

        # ----------------------------
        # Survival reward
        # ----------------------------
        if not curr_o.get("dead", False):
            reward += 0.01

        # ----------------------------
        # Movement reward (XZ plane)
        # ----------------------------
        pos = curr_o.get("pos", {})
        x, z = pos.get("x"), pos.get("z")

        if prev_o is not None:
            prev_pos = prev_o.get("pos", {})
            px, pz = prev_pos.get("x"), prev_pos.get("z")
            if None not in (x, z, px, pz):
                dist = math.sqrt((x - px) ** 2 + (z - pz) ** 2)
                if dist > 0.05:
                    reward += 0.1

        # ----------------------------
        # Damage penalty
        # ----------------------------
        health = curr_o.get("health")
        prev_health = prev_o.get("health") if prev_o is not None else None
        if prev_health is not None and health is not None and health < prev_health:
            reward -= (prev_health - health) * 0.5

        # ----------------------------
        # Death penalty
        # ----------------------------
        if curr_o.get("dead", False):
            reward -= 10.0
            done = True

        return reward, done
