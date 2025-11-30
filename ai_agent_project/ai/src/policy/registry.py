from __future__ import annotations
import pathlib
from typing import Any, Dict

from ai.src.policy.rl.dqn.agent import DQNPolicy, OnlineDQNPolicy

def _project_root() -> pathlib.Path:
    return pathlib.Path(__file__).resolve().parents[3]

def _resolve_checkpoint_path(raw: str | None) -> str | None:
    if not raw: return None
    p = pathlib.Path(raw)
    return str(p) if p.is_absolute() else str((_project_root() / raw).resolve())

def build_policy_from_config(runtime_cfg: Dict[str, Any]):
    pol_cfg = runtime_cfg.get("policy", {})
    ptype = pol_cfg.get("type", "dqn")

    dqn_cfg = pol_cfg.get("dqn", {})
    ckpt = _resolve_checkpoint_path(dqn_cfg.get("checkpoint_path"))

    ray_cfg = runtime_cfg.get("raycasts", {})
    max_ray = ray_cfg.get("max_dist", pol_cfg.get("max_ray_dist", 20.0))

    device = pol_cfg.get("device", "cpu")

    if ptype == "online_dqn":
        return OnlineDQNPolicy.FromCheckpoint(
            ckpt,
            max_ray_dist=max_ray,
            device=device
        )

    if ptype == "dqn":
        return DQNPolicy.FromCheckpoint(
            ckpt,
            max_ray_dist=max_ray,
            device=device
        )

    raise ValueError(f"Unknown policy type: {ptype}")
