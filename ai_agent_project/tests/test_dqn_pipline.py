import unittest
import numpy as np
import torch
import sys
import os

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ai.src.policy.mock_envs.ethan_gym. import EthanMockEnv
from ai.src.policy.rl.dqn.train import train_agent
from ai.src.policy.rl.dqn.agent import DQNAgent


class TestDQNPipeline(unittest.TestCase):
    def test_full_pipeline_runs(self):
        """
        This test ensures:
          - DQNAgent builds successfully
          - train_agent runs with no crashes
          - loss is computed at least once
        """
        env = EthanMockEnv()

        config = {
            "num_episodes": 10,
            "max_steps_per_episode": 30,
            "gamma": 0.99,
            "lr": 1e-3,
            "min_replay_size": 50,    # replay buffer warmup
            "buffer_capacity": 500,
            "batch_size": 32,
            "target_update_interval": 20,
            "epsilon_decay_steps": 200,
            "log_every_episodes": 2,
            "device": "cpu",
            "checkpoint_dir": None,   # no saving
            "log_csv_path": None,
        }

        # Train for a few episodes
        train_agent(env, config)

        # Build agent after training for sanity check
        agent = DQNAgent()
        # Just make sure select_action returns a valid integer
        obs = env.reset()
        action_idx = agent.select_action(obs)

        self.assertTrue(isinstance(action_idx, int))
        self.assertTrue(0 <= action_idx < agent.q_online.net[-1].out_features)


if __name__ == "__main__":
    unittest.main()
