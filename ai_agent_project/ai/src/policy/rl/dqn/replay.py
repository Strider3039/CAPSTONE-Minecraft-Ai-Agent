from __future__ import annotations
import numpy as np

from ai.src.policy.obs_encoding import EncodeObservation, OBS_DIM

class ReplayBuffer:
    """
    A simple replay buffer for storing and sampling experience tuples.
    """

    def __init__(self, capacity: int):
        self.capacity = capacity

        self.states = np.zeros((capacity, OBS_DIM), dtype=np.float32)
        self.next_states = np.zeros((capacity, OBS_DIM), dtype=np.float32)
        self.actions = np.zeros((capacity,), dtype=np.int64)
        self.rewards = np.zeros((capacity,), dtype=np.float32)
        self.dones = np.zeros((capacity,), dtype=np.float32)

        self.idx = 0
        self.size = 0
    
    def __len__(self) -> int:
        return self.size
    
    def add(
        self,
        state: np.ndarray,
        action: int,
        reward: float,
        next_state: np.ndarray,
        done: bool,
    ) -> None:
        """
        Add a new experience tuple to the buffer.
        """
        i = self.idx

        self.states[i] = state
        self.actions[i] = action
        self.rewards[i] = reward
        self.next_states[i] = next_state
        self.dones[i] = float(done)

        self.idx = (self.idx + 1) % self.capacity
        self.size = min(self.size + 1, self.capacity)

    def sample(self, batch_size: int) -> tuple[str, np.ndarray]:
        if self.size == 0:
            raise ValueError("Cannot sample from an empty buffer.")
        
        batch_size = min(batch_size, self.size)
        idxs = np.random.choice(self.size, size=batch_size, replace=False)

        return (
            self.states[idxs],
            self.actions[idxs],
            self.rewards[idxs],
            self.next_states[idxs],
            self.dones[idxs],
        )