# -----------------------------------------------------------------------------
# Replay buffer tests
# -----------------------------------------------------------------------------
import numpy as np
import os
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ai.policy.obs_encoding import OBS_DIM
from ai.rl.dqn.replay import ReplayBuffer


def test_add_and_sample():
    """Adding transitions and sampling a batch should return tensors with the right shapes."""
    buf = ReplayBuffer(capacity=10)

    s = np.zeros(OBS_DIM, dtype=np.float32)
    s_next = np.ones(OBS_DIM, dtype=np.float32)

    for i in range(5):
        buf.add(s, i, float(i), s_next, done=(i % 2 == 0))

    assert len(buf) == 5

    states, actions, rewards, next_states, dones = buf.sample(batch_size=3)
    assert states.shape[1] == OBS_DIM
    assert next_states.shape[1] == OBS_DIM
    assert actions.shape[0] == 3
