# ai/policy/rl/dqn/reward_engine.py

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from .reward_components import (
    RewardComponent,
    GenericRewardComponent,
    BlockRewardComponent,
    MobRewardComponent,
    default_components,
)


@dataclass
class RewardEngine:
    """
    Modular reward engine: composes pluggable RewardComponents (generic, blocks, mobs).
    Config is dispatched per component; add new components by extending reward_components
    and passing them to the constructor.

    Observation payload (expected keys):
      - pose, rays, front_clear, world, inventory, collision (for generic)
      - last_block_broken / recent_events (optional, for block rewards)
      - last_mob_killed / recent_events (optional, for mob rewards)
    """

    # Episode bookkeeping (engine-level)
    episode_return: float = 0.0
    steps: int = 0
    max_steps_per_episode: int = 2000  # ~100 seconds at 20 Hz

    # Optional curriculum / logging
    phase: Optional[str] = field(default=None, init=False)

    # Components (generic, blocks, mobs); override for custom composition
    _components: List[RewardComponent] = field(default_factory=list, init=False)

    def __post_init__(self) -> None:
        if not self._components:
            self._components = default_components()

    def apply_config(self, cfg: Dict[str, Any]) -> None:
        """
        Update from runtime.policy.reward. Each component receives its slice via config_key:
          - config_key is None => full reward dict (generic)
          - config_key "blocks" => cfg["blocks"] (dict of block_id -> reward)
          - config_key "mobs" => cfg["mobs"] (dict of entity_type -> reward)
        """
        if not isinstance(cfg, dict):
            return
        # Engine-level
        if "max_steps_per_episode" in cfg and cfg["max_steps_per_episode"] is not None:
            try:
                self.max_steps_per_episode = int(cfg["max_steps_per_episode"])
            except (TypeError, ValueError):
                pass
        # Dispatch by component.config_key
        for c in self._components:
            key = getattr(c, "config_key", None)
            if key is None:
                slice_cfg = cfg
            else:
                slice_cfg = cfg.get(key) if isinstance(cfg.get(key), dict) else {}
            c.apply_config(slice_cfg)

    def reset_episode(self) -> None:
        """Called when an episode ends."""
        self.episode_return = 0.0
        self.steps = 0
        for c in self._components:
            c.reset_episode()

    def compute(
        self,
        prev_obs: Optional[dict],
        curr_obs: dict,
    ) -> tuple[float, bool]:
        """
        Sum reward from all components, update episode bookkeeping, check step limit.

        Returns:
            (reward, done)
        """
        reward = sum(c.compute(prev_obs, curr_obs) for c in self._components)
        self.episode_return += reward
        self.steps += 1
        done = self.steps >= self.max_steps_per_episode
        return float(reward), bool(done)
