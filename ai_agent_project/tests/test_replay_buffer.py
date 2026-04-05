import math
import unittest
import numpy as np
import os
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ai.policy.obs_encoding import EncodeObservation, OBS_DIM
from ai.rl.dqn.replay import ReplayBuffer


class TestReplayBuffer(unittest.TestCase):
    def test_add_and_sample(self):
        buf = ReplayBuffer(capacity=10)

        s = np.zeros(OBS_DIM, dtype=np.float32)
        s_next = np.ones(OBS_DIM, dtype=np.float32)

        for i in range(5):
            buf.add(s, i, float(i), s_next, done=(i % 2 == 0))

        self.assertEqual(len(buf), 5)

        states, actions, rewards, next_states, dones = buf.sample(batch_size=3)
        self.assertEqual(states.shape[1], OBS_DIM)
        self.assertEqual(next_states.shape[1], OBS_DIM)
        self.assertEqual(actions.shape[0], 3)


if __name__ == "__main__":
    unittest.main()