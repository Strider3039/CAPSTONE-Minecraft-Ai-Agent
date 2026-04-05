# ai/policy/mock_envs/ethan_gym.py

import numpy as np
from ai.policy.obs_encoding import OBS_DIM

class EthanMockEnv:
    """
    A tiny 2D navigation toy environment.

    Obs vector is always (OBS_DIM,) filled like this:
      - obs[0], obs[2]      -> agent x,z position (we pretend y is irrelevant)
      - obs[3:7], obs[7:]   -> zeros (not testing yaw/pitch or rays)
    """

    def __init__(self, goal=(5.0, 5.0), max_steps=50):
        self.goal_x, self.goal_z = goal
        self.max_steps = max_steps
        self.reset()

    def _build_obs(self):
        """Create an obs vector with real x,z and zeros everywhere else."""
        obs = np.zeros(OBS_DIM, dtype=np.float32)
        obs[0] = self.x
        obs[2] = self.z
        return obs

    def reset(self):
        self.x = 0.0
        self.z = 0.0
        self.steps = 0
        return self._build_obs()

    def step(self, action_idx: int):
        # Simple physics
        if action_idx == 1:      # move_forward
            self.z += 0.5
        elif action_idx == 2:    # move_back
            self.z -= 0.5
        elif action_idx == 3:    # strafe_left
            self.x -= 0.5
        elif action_idx == 4:    # strafe_right
            self.x += 0.5
        # other actions (look, jump) do nothing but are allowed

        self.steps += 1

        # Distance to goal
        dist = ((self.x - self.goal_x)**2 + (self.z - self.goal_z)**2) ** 0.5
        reward = -0.01

        done = False
        if dist < 0.5:
            reward = 1.0
            done = True

        if self.steps >= self.max_steps:
            done = True

        return self._build_obs(), reward, done, {}
