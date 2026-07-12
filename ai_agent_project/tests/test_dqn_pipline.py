# -----------------------------------------------------------------------------
# DQN training pipeline smoke test
#
# One end-to-end sanity check: build a DQN agent, run a short training loop on
# the mock environment, and confirm select_action returns a valid index. This is
# not a correctness proof. It just makes sure the training stack still wires up.
#
# Catches import errors, shape mismatches, or broken train loops that would only
# show up after you kick off a long experiment.
# -----------------------------------------------------------------------------
import unittest
import numpy as np
import torch
import sys
import os

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from experiments.mock_envs.dqn_mock import EthanMockEnv
from ai.rl.dqn.train import train_agent
from ai.rl.dqn.agent import DQNAgent


class TestDQNPipeline(unittest.TestCase):
    def test_full_pipeline_runs(self):
        """Smoke test: agent builds, training runs a few episodes, and select_action returns something valid."""
        env = EthanMockEnv()

        config = {
            "num_episodes": 10,
            "max_steps_per_episode": 30,
            "gamma": 0.99,
            "lr": 1e-3,
            "min_replay_size": 50,    # need a few transitions before training kicks in
            "buffer_capacity": 500,
            "batch_size": 32,
            "target_update_interval": 20,
            "epsilon_decay_steps": 200,
            "log_every_episodes": 2,
            "device": "cpu",
            "checkpoint_dir": None,   # don't write checkpoints during the test
            "log_csv_path": None,
        }

        # quick training run. We're checking it doesn't crash.
        train_agent(env, config)

        # fresh agent, make sure it can pick an action from an observation
        agent = DQNAgent()
        obs = env.reset()
        action_idx = agent.SelectAction(obs)

        self.assertTrue(isinstance(action_idx, int))
        self.assertTrue(0 <= action_idx < agent.qNet.net[-1].out_features)


if __name__ == "__main__":
    unittest.main()
