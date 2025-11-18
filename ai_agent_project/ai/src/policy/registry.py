from __future__ import annotations
from typing import Any, Dict

from .base import Policy

def build_policy_from_config(cfg: Dict[str, Any]) -> Policy:
    policy_cfg = cfg.get("policy", {})
    policy_type = policy_cfg.get("type", "dqn")
