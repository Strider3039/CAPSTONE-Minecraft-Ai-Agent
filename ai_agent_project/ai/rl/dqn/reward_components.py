# ai/src/policy/rl/dqn/reward_components.py
"""
Modular reward components for the DQN reward engine.

To add a new reward type (e.g. dimension, biome, custom event):
  1. Implement RewardComponent (apply_config, compute, optional reset_episode).
  2. Set config_key = "my_key" so reward.my_key in YAML is passed to apply_config.
  3. Append the class to default_components() in this file.
  RewardEngine will dispatch config and sum compute() automatically.
"""

from __future__ import annotations
from abc import ABC, abstractmethod
from typing import Any, Dict, Optional
import math


def _extract_body(obs: dict | None) -> Dict[str, Any]:
    """Observation body is obs["payload"]."""
    if not obs or not isinstance(obs, dict):
        return {}
    payload = obs.get("payload")
    return payload if isinstance(payload, dict) else {}


class RewardComponent(ABC):
    """
    Pluggable reward component. Hot-reload: apply_config receives its config slice.
    config_key: None = receive full reward dict (e.g. generic); str = receive cfg[config_key].
    """

    config_key: Optional[str] = None  # None => full reward cfg; "blocks" => cfg["blocks"]

    @abstractmethod
    def apply_config(self, cfg: Dict[str, Any]) -> None:
        """Update from config (e.g. reward.blocks or reward.generic slice)."""
        pass

    @abstractmethod
    def compute(self, prev_obs: Optional[dict], curr_obs: dict) -> float:
        """Return this step's reward contribution."""
        pass

    def reset_episode(self) -> None:
        """Called when episode ends; override if component has episode state."""
        pass


class GenericRewardComponent(RewardComponent):
    """
    Survival, movement, collision, and inventory rewards.
    Config: flat dict with survival_reward, step_penalty, move_scale, etc.
    """

    config_key = None  # receives full reward dict (minus keys claimed by other components)

    def __init__(self) -> None:
        self.survival_reward: float = 0.001
        self.step_penalty: float = -0.001
        self.move_scale: float = 1.0
        self.max_move_reward: float = 0.1
        self.no_progress_penalty: float = -0.02
        self.front_clear_bonus: float = 0.005
        self.item_pickup_reward: float = 0.05
        self._last_pos: Optional[Dict[str, float]] = None
        self._last_hotbar: Optional[list] = None

    def apply_config(self, cfg: Dict[str, Any]) -> None:
        if not isinstance(cfg, dict):
            return
        for key in (
            "survival_reward", "step_penalty", "move_scale", "max_move_reward",
            "no_progress_penalty", "front_clear_bonus", "item_pickup_reward",
        ):
            if key in cfg and cfg[key] is not None:
                try:
                    setattr(self, key, float(cfg[key]))
                except (TypeError, ValueError):
                    pass

    def reset_episode(self) -> None:
        self._last_pos = None
        self._last_hotbar = None

    def compute(self, prev_obs: Optional[dict], curr_obs: dict) -> float:
        body_prev = _extract_body(prev_obs)
        body_curr = _extract_body(curr_obs)
        reward = 0.0
        reward += self.survival_reward
        reward += self.step_penalty
        reward += self._movement_reward(body_prev, body_curr)
        reward += self._collision_reward(body_curr)
        reward += self._inventory_reward(body_prev, body_curr)
        return float(reward)

    def _movement_reward(
        self,
        body_prev: Optional[Dict[str, Any]],
        body_curr: Dict[str, Any],
    ) -> float:
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

    @staticmethod
    def _extract_pos(body: Optional[Dict[str, Any]]) -> Optional[Dict[str, float]]:
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
        reward = 0.0
        collision = body_curr.get("collision", {}) or {}
        if bool(collision.get("no_progress", False)):
            reward += self.no_progress_penalty
        if bool(body_curr.get("front_clear", False)):
            reward += self.front_clear_bonus
        return reward

    def _inventory_reward(
        self,
        body_prev: Optional[Dict[str, Any]],
        body_curr: Dict[str, Any],
    ) -> float:
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
                    if curr_count > prev_count:
                        reward += self.item_pickup_reward
                except (TypeError, ValueError):
                    continue
        self._last_hotbar = inv_curr
        return reward

    @staticmethod
    def _extract_hotbar(body: Optional[Dict[str, Any]]) -> Optional[list]:
        if not body or not isinstance(body, dict):
            return None
        inv = body.get("inventory", {})
        if not isinstance(inv, dict):
            return None
        hotbar = inv.get("hotbar")
        return hotbar if isinstance(hotbar, list) else None


class BlockRewardComponent(RewardComponent):
    """
    Custom rewards per block (e.g. break/place).
    Config: dict of block_id -> reward, e.g. {"minecraft:diamond_ore": 1.0, "minecraft:log": 0.1}.

    Observation: when the mod sends block events, payload may include:
      - last_block_broken: { "block_id": "minecraft:stone", ... }  (optional)
      - recent_events: [{ "type": "block_broken", "block_id": "..." }, ...]  (optional)
    """

    config_key = "blocks"

    def __init__(self) -> None:
        self.rewards: Dict[str, float] = {}

    def apply_config(self, cfg: Dict[str, Any]) -> None:
        if not isinstance(cfg, dict):
            return
        self.rewards = {}
        for k, v in cfg.items():
            try:
                self.rewards[str(k)] = float(v)
            except (TypeError, ValueError):
                pass

    def compute(self, prev_obs: Optional[dict], curr_obs: dict) -> float:
        payload = _extract_body(curr_obs)
        # Single most recent event
        last = payload.get("last_block_broken")
        if isinstance(last, dict):
            block_id = last.get("block_id") or last.get("id")
            if block_id is not None:
                return self.rewards.get(str(block_id), 0.0)
        # Or first event from recent_events list
        events = payload.get("recent_events")
        if isinstance(events, list):
            for ev in events:
                if isinstance(ev, dict) and ev.get("type") in ("block_broken", "block_break"):
                    bid = ev.get("block_id") or ev.get("id")
                    if bid is not None:
                        return self.rewards.get(str(bid), 0.0)
        return 0.0


class MobRewardComponent(RewardComponent):
    """
    Custom rewards per mob/entity (e.g. kill).
    Config: dict of entity_type_id -> reward, e.g. {"minecraft:zombie": 0.5, "minecraft:skeleton": 0.5}.
    Observation: when the mod sends mob events, payload may include last_mob_killed or recent_events.
    """

    config_key = "mobs"

    def __init__(self) -> None:
        self.rewards: Dict[str, float] = {}

    def apply_config(self, cfg: Dict[str, Any]) -> None:
        if not isinstance(cfg, dict):
            return
        self.rewards = {}
        for k, v in cfg.items():
            try:
                self.rewards[str(k)] = float(v)
            except (TypeError, ValueError):
                pass

    def compute(self, prev_obs: Optional[dict], curr_obs: dict) -> float:
        payload = _extract_body(curr_obs)
        last = payload.get("last_mob_killed")
        if isinstance(last, dict):
            entity_type = last.get("entity_type") or last.get("id")
            if entity_type is not None:
                return self.rewards.get(str(entity_type), 0.0)
        events = payload.get("recent_events")
        if isinstance(events, list):
            for ev in events:
                if isinstance(ev, dict) and ev.get("type") in ("mob_killed", "entity_killed"):
                    eid = ev.get("entity_type") or ev.get("id")
                    if eid is not None:
                        return self.rewards.get(str(eid), 0.0)
        return 0.0


def default_components() -> list[RewardComponent]:
    """Components used by RewardEngine when none are passed."""
    return [
        GenericRewardComponent(),
        BlockRewardComponent(),
        MobRewardComponent(),
    ]
