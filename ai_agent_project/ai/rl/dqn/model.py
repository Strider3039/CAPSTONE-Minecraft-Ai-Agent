from typing import Sequence

import torch
import torch.nn as nn
import torch.nn.functional as F

class QNetwork(nn.Module):
    """
    Simple MLP that maps an observation vector to Q-values for each action.
    """

    def __init__(
        self,
        obs_dim: int,
        num_actions: int,
        hidden_sizes: Sequence[int] = (128, 128),
    ) -> None:
        super().__init__()

        layers = []
        inputSize = obs_dim

        for h in hidden_sizes:
            layers.append(nn.Linear(inputSize, h))
            layers.append(nn.ReLU())
            inputSize = h

        layers.append(nn.Linear(inputSize, num_actions))
        self.net = nn.Sequential(*layers)

    def forward(self, obsBatch: torch.Tensor) -> torch.Tensor:
        """
        Forward pass through the network.

        Args:
            obsBatch: Tensor of shape (batch_size, obs_dim)

        Returns:
            Tensor of shape (batch_size, num_actions) with Q-values.
        """
        return self.net(obsBatch)