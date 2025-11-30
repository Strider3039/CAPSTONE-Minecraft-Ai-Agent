# ai/src/policy/registry.py

from __future__ import annotations
import os

from ai.src.policy.base import Policy
from ai.src.policy.rl.dqn.agent import DQNPolicy, OnlineDQNPolicy
from ai.src.policy.obs_encoding import OBS_DIM
from ai.src.policy.action_space import NUM_ACTIONS


# ---------------------------------------------------------------------
# ENTRY POINT
# ---------------------------------------------------------------------

def build_policy_from_config(cfg: dict) -> Policy:
    """
    Builds whichever policy the YAML runtime config requests.
    Currently loads ONLINE DQN.
    """
    policy_cfg = cfg.get("policy", {})
    dqn_cfg = policy_cfg.get("dqn", {})

    checkpoint_path = dqn_cfg.get("checkpoint_path", None)

    # Convert "../" relative paths to absolute project path
    if checkpoint_path and checkpoint_path.startswith("../"):
        # ai/src/policy/registry.py -> go up 3 dirs to project/
        base = os.path.dirname(os.path.dirname(os.path.dirname(__file__)))
        abs_path = os.path.normpath(os.path.join(base, checkpoint_path))
    else:
        abs_path = checkpoint_path

    print(f"[registry] Using OnlineDQNPolicy with checkpoint: {abs_path}")

    max_ray_dist = cfg.get("raycasts", {}).get("max_dist", 6.0)

    return OnlineDQNPolicy.FromCheckpoint(
        checkpoint_path=abs_path,
        max_ray_dist=max_ray_dist,
        device="cpu",
        hidden_sizes=(128, 128),
    )
