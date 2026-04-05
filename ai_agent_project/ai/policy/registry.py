from __future__ import annotations
import pathlib
from typing import Any, Dict

from ai.utils.runtime_paths import data_dir, resource_search_roots

# Legacy default.yaml used policy/runs/...; save_initial_checkpoint and Data layout use checkpoints/...
_LEGACY_CHECKPOINT_ALIASES: dict[str, tuple[str, ...]] = {
    "policy/runs/checkpoints/dqn_initial.pt": ("checkpoints/dqn_initial.pt",),
}


def _resolve_checkpoint_path(raw: str | None) -> str | None:
    if not raw:
        return None
    p = pathlib.Path(raw)
    if p.is_absolute():
        return str(p)

    rels: list[str] = [str(raw).replace("\\", "/").strip("/")]
    for alias in _LEGACY_CHECKPOINT_ALIASES.get(rels[0], ()):
        if alias not in rels:
            rels.append(alias)

    roots = resource_search_roots()
    for rel in rels:
        for root in roots:
            candidate = (root / rel).resolve()
            if candidate.is_file():
                return str(candidate)
    return None


def _online_dqn_resume_path() -> str | None:
    """
    Path written on WebSocket disconnect (and periodically) by OnlineDQNPolicy — prefer this over
    the seed checkpoint so PLAYER mode continues training after leaving the world or closing the game.
    """
    p = data_dir() / "online_dqn_latest.pt"
    return str(p.resolve()) if p.is_file() else None


def build_policy_from_config(runtime_cfg: Dict[str, Any]):
    from ai.rl.dqn.agent import DQNPolicy, OnlineDQNPolicy

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
        resume = _online_dqn_resume_path()
        load_ckpt = resume or ckpt
        return OnlineDQNPolicy.FromCheckpoint(
            load_ckpt,
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
