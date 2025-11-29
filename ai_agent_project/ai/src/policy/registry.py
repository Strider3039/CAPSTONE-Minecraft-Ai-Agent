from typing import Any, Dict

# Base Policy interface
from ai.src.policy.base import Policy

# DQN policy implementation
from ai.src.policy.rl.dqn.agent import DQNPolicy


# -------------------------------------------------------------
# Helpers
# -------------------------------------------------------------
def _get_policy_type(runtime_cfg: Dict[str, Any]) -> str:
    """
    Return policy type from runtime.yaml (default: 'dqn').
    """
    return runtime_cfg.get("policy", {}).get("type", "dqn")


def _get_device(runtime_cfg: Dict[str, Any]) -> str:
    """
    Return device string ('cpu' or 'cuda').
    """
    return runtime_cfg.get("policy", {}).get("device", "cpu")


def _get_max_ray_dist(runtime_cfg: Dict[str, Any]) -> float:
    """
    Return max ray distance for observation encoding.
    """
    return float(runtime_cfg.get("raycasts", {}).get("max_dist", 10.0))


# -------------------------------------------------------------
# Build DQN Policy
# -------------------------------------------------------------
def _build_dqn_policy(runtime_cfg: Dict[str, Any]) -> Policy:
    """
    Build and return a DQNPolicy using runtime.yaml settings.

    Requires:
      policy.dqn.checkpoint_path
    """
    dqn_cfg = runtime_cfg.get("policy", {}).get("dqn", {})
    checkpoint_path = dqn_cfg.get("checkpoint_path")

    if not checkpoint_path:
        raise ValueError("Missing 'policy.dqn.checkpoint_path' in runtime.yaml")

    hidden_sizes = dqn_cfg.get("hidden_sizes", [128, 128])
    device = _get_device(runtime_cfg)
    max_ray_dist = _get_max_ray_dist(runtime_cfg)

    # IMPORTANT: Correct constructor (capital F + C)
    return DQNPolicy.FromCheckpoint(
        checkpoint_path=checkpoint_path,
        max_ray_dist=max_ray_dist,
        device=device,
        hidden_sizes=hidden_sizes,
    )


# -------------------------------------------------------------
# (Placeholder) Goal Nav Policy
# -------------------------------------------------------------
def _build_goal_nav_policy(runtime_cfg: Dict[str, Any]) -> Policy:
    """
    Placeholder for legacy scripted navigation policy.
    """
    raise NotImplementedError("GoalNavPolicy not wired into registry yet.")


# -------------------------------------------------------------
# Public Entry Point
# -------------------------------------------------------------
def build_policy_from_config(runtime_cfg: Dict[str, Any]) -> Policy:
    """
    Build the correct Policy object based on runtime.yaml.
    """
    policy_type = _get_policy_type(runtime_cfg)

    if policy_type == "dqn":
        return _build_dqn_policy(runtime_cfg)

    if policy_type == "goal_nav":
        return _build_goal_nav_policy(runtime_cfg)

    raise ValueError(f"Unknown policy.type: {policy_type!r}")
