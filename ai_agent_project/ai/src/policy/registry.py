from __future__ import annotations
import pathlib
from typing import Any, Dict

from ai.src.utils.runtime_paths import resource_search_roots

def _resolve_checkpoint_path(raw: str | None) -> str | None:
    if not raw:
        return None
    p = pathlib.Path(raw)
    if p.is_absolute():
        return str(p)
    for root in resource_search_roots():
        candidate = (root / raw).resolve()
        if candidate.is_file():
            return str(candidate)
    return str((resource_search_roots()[0] / raw).resolve())

def build_policy_from_config(runtime_cfg: Dict[str, Any]):
    from ai.src.policy.rl.dqn.agent import DQNPolicy, OnlineDQNPolicy

    pol_cfg = runtime_cfg.get("policy", {})
    ptype = pol_cfg.get("type", "dqn")

    dqn_cfg = pol_cfg.get("dqn", {})
    ckpt = _resolve_checkpoint_path(dqn_cfg.get("checkpoint_path"))
    save_every_steps = dqn_cfg.get("save_every_steps")  # e.g. 6000 = ~5 min at 20 Hz

    ray_cfg = runtime_cfg.get("raycasts", {})
    max_ray = ray_cfg.get("max_dist", pol_cfg.get("max_ray_dist", 20.0))

    device = pol_cfg.get("device", "cpu")

    reward_cfg = pol_cfg.get("reward")
    dqn_cfg = pol_cfg.get("dqn")

    if ptype == "online_dqn":
        train_every_n = max(1, int(pol_cfg.get("train_every_n", 1)))
        log_disk_every_n = max(1, int(pol_cfg.get("log_disk_every_n", 1)))
        return OnlineDQNPolicy.FromCheckpoint(
            ckpt,
            max_ray_dist=max_ray,
            device=device,
            save_every_steps=save_every_steps,
            reward_cfg=reward_cfg if isinstance(reward_cfg, dict) else None,
            dqn_cfg=dqn_cfg if isinstance(dqn_cfg, dict) else None,
            train_every_n=train_every_n,
            log_disk_every_n=log_disk_every_n,
        )

    if ptype == "dqn":
        return DQNPolicy.FromCheckpoint(
            ckpt,
            max_ray_dist=max_ray,
            device=device
        )

    raise ValueError(f"Unknown policy type: {ptype}")
