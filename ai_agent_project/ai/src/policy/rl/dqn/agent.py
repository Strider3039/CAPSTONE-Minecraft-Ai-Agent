from __future__ import annotations
import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim

from ai.src.policy.rl.dqn.model import QNetwork
from ai.src.policy.action_space import NUM_ACTIONS, ToMinecraftControls
from ai.src.policy.obs_encoding import OBS_DIM, EncodeObservation
from .model import QNetwork
from .replay import ReplayBuffer

def EpsilonGreedyAction(
    qNet: QNetwork,
    obsVec: np.ndarray,
    epsilon: float,
    device: str = "cpu",

) -> int:
    """
    Select an action index using epsilon-greedy strategy.

    - With probability epsilon: random action.
    - Otherwise: argmax_a Q(s, a).
    """