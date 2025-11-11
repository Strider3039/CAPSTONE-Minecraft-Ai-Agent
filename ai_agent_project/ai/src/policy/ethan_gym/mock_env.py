"""
Mock Minecraft Gymnasium Environment (v1)
- Discrete(7) actions
- 10D observation vector
- Reward shaping and episode logic aligned with real-bridge spec
"""

from __future__ import annotations
import math
import random
from dataclasses import dataclass
from typing import Dict, Tuple, Optional, Any
import csv
import os

import numpy as np

try:
    import gymnasium as gym
    from gymnasium import spaces
except Exception as e:
    # Fallback types to keep editor happy if Gymnasium isn't installed yet.
    class _Dummy:
        def __getattr__(self, k): return object()
    gym = _Dummy()
    class _Spaces:  # minimal fallbacks
        class Box: ...
        class Discrete: ...
    spaces = _Spaces() 


# -------------------------
# Defaults matching Step 1
# -------------------------

DEFAULT_CFG = {
    "tick_hz": 20,
    "action_repeat_k": 4,
    "max_steps": 600,
    "goal_radius": 1.5,
    "max_range": 40.0,
    "max_height": 20.0,
    "max_speed": 0.2,
    "start_yaw_random": False,
    "goal_bounds": {
        "min_xy": -15.0,
        "max_xy":  15.0,
        "min_dist": 5.0,
    },
    "reward": {
        "progress_alpha": 1.0,
        "step_penalty": -0.01,
        "stuck_penalty": -5.0,
        "death_penalty": -20.0,
        "goal_bonus": 100.0,
        "stuck_speed_thresh": 0.02,
        "stuck_ticks": 15,
    },
    "action_magnitudes": {
        "look_yaw_deg": 8.0,
        "look_pitch_deg": 4.0,  # not used in 2D dynamics but present for parity
        "move_forward": 1,
        "jump": True,           # not used in 2D dynamics
    },
}


@dataclass
class MockState:
    # 2.5D pose (y used only for dy_to_goal in obs; dynamics are 2D)
    x: float = 0.0
    y: float = 0.0
    z: float = 0.0
    yaw_deg: float = 0.0      # heading in degrees
    pitch_deg: float = 0.0    # kept for parity; no vertical dynamics in v1
    v_forward: float = 0.0    # forward velocity scalar
    on_ground: bool = True

    # Book-keeping
    step_index: int = 0
    stuck_counter: int = 0
    last_progress: float = 0.0

    

class MockMinecraftEnv(getattr(gym, "Env", object)):
    """
    Gymnasium-style mock environment for rapid DQN iteration.
    Observation (10 dims):
        [dx_norm, dz_norm, dy_norm, horiz_dist_norm,
         sin_yaw, cos_yaw, pitch_norm,
         on_ground, speed_norm, stuck_flag]
    Action space: Discrete(7)
        0: TurnLeftSmall    (yaw -= 8)
        1: TurnRightSmall   (yaw += 8)
        2: LookUpSmall      (pitch -= 4)   (no effect on dynamics)
        3: LookDownSmall    (pitch += 4)   (no effect on dynamics)
        4: MoveForward      (v_forward = +1 unit)
        5: Stop             (v_forward = 0)
        6: Jump             (no effect in v1; kept for parity)
    """
    metadata = {"render_modes": []}

    def __init__(self, cfg: Optional[Dict[str, Any]] = None, seed: Optional[int] = None):
        self.cfg = DEFAULT_CFG.copy()
        if cfg:
            # shallow-merge keys (nested dicts merged manually)
            for k, v in cfg.items():
                if k in self.cfg and isinstance(self.cfg[k], dict) and isinstance(v, dict):
                    self.cfg[k].update(v)
                else:
                    self.cfg[k] = v

        self.rng = random.Random(seed)
        self.np_rng = np.random.default_rng(seed)

        # Spaces
        low = np.array([-1, -1, -1, 0, -1, -1, -1, 0, 0, 0], dtype=np.float32)
        high = np.array([ 1,  1,  1, 1,  1,  1,  1, 1, 1, 1], dtype=np.float32)
        try:
            self.observation_space = spaces.Box(low=low, high=high, dtype=np.float32)
            self.action_space = spaces.Discrete(7)
        except Exception:
            self.observation_space = None
            self.action_space = None

        # Internal state
        self.state = MockState()
        self.goal = np.zeros(3, dtype=np.float32)
        self.prev_dist = 0.0
        self.done = False

        # Cached cfg
        self.k = int(self.cfg["action_repeat_k"])
        self.max_steps = int(self.cfg["max_steps"])
        self.max_speed = float(self.cfg["max_speed"])
        self.max_range = float(self.cfg["max_range"])
        self.max_height = float(self.cfg["max_height"])
        self.goal_radius = float(self.cfg["goal_radius"])

        self.alpha = float(self.cfg["reward"]["progress_alpha"])
        self.step_pen = float(self.cfg["reward"]["step_penalty"])
        self.stuck_pen = float(self.cfg["reward"]["stuck_penalty"])
        self.goal_bonus = float(self.cfg["reward"]["goal_bonus"])
        self.stuck_speed_thresh = float(self.cfg["reward"]["stuck_speed_thresh"])
        self.stuck_ticks = int(self.cfg["reward"]["stuck_ticks"])

        self.yaw_step = float(self.cfg["action_magnitudes"]["look_yaw_deg"])
        self.pitch_step = float(self.cfg["action_magnitudes"]["look_pitch_deg"])

    # ---------------
    # Gym API
    # ---------------
    def reset(self, *, seed: Optional[int] = None, options: Optional[dict] = None):
        if seed is not None:
            self.rng.seed(seed)
            self.np_rng = np.random.default_rng(seed)

        self.state = MockState()
        if self.cfg.get("start_yaw_random", False):
            self.state.yaw_deg = self.rng.uniform(-180.0, 180.0)
        self._sample_goal()
        self.prev_dist = self._horiz_dist(self.state, self.goal)
        self.done = False

        obs = self._encode_obs(self.state, self.goal, stuck_flag=False)
        info = {
            "raw_dist": float(self.prev_dist),
            "progress": 0.0,
            "stuck_triggered": False,
            "goal_reached": False,
            "step_index": 0,
        }
        return obs, info

    def step(self, action: int):
        if self.done:
            raise RuntimeError("Call reset() before step() — episode is done.")

        # Apply the chosen action for k ticks
        stuck_triggered = False
        for _ in range(self.k):
            self._apply_action(action)
            self._simulate_dynamics()
            self.state.step_index += 1

        # Compute reward
        new_dist = self._horiz_dist(self.state, self.goal)
        progress = self.prev_dist - new_dist
        reward = self.alpha * progress + self.step_pen

        # Stuck detection (based on average speed/progress over k ticks)
        avg_speed = abs(progress) / max(self.k, 1)
        if avg_speed < self.stuck_speed_thresh:
            self.state.stuck_counter += 1
            if self.state.stuck_counter >= self.stuck_ticks:
                reward += self.stuck_pen
                stuck_triggered = True
                self.state.stuck_counter = 0  # trigger once then reset counter
        else:
            self.state.stuck_counter = 0

        # Goal check
        goal_reached = new_dist < self.goal_radius
        if goal_reached:
            reward += self.goal_bonus
            self.done = True

        # Max step check
        if self.state.step_index >= self.max_steps:
            self.done = True

        obs = self._encode_obs(self.state, self.goal, stuck_flag=stuck_triggered)
        info = {
            "raw_dist": float(new_dist),
            "progress": float(progress),
            "stuck_triggered": stuck_triggered,
            "goal_reached": goal_reached,
            "step_index": int(self.state.step_index),
        }
        self.prev_dist = new_dist

        return obs, float(reward), bool(self.done), False, info  # (obs, reward, terminated, truncated, info)

    # ---------------
    # Helpers
    # ---------------
    def _sample_goal(self):
        b = self.cfg["goal_bounds"]
        min_xy, max_xy, min_dist = float(b["min_xy"]), float(b["max_xy"]), float(b["min_dist"])

        # ensure non-trivial placement
        while True:
            gx = self.rng.uniform(min_xy, max_xy)
            gz = self.rng.uniform(min_xy, max_xy)
            if math.hypot(gx - self.state.x, gz - self.state.z) >= min_dist:
                break
        self.goal = np.array([gx, 0.0, gz], dtype=np.float32)

    def _apply_action(self, action: int):
        # Heading adjustments
        if action == 0:       # TurnLeftSmall
            self.state.yaw_deg -= self.yaw_step
        elif action == 1:     # TurnRightSmall
            self.state.yaw_deg += self.yaw_step
        elif action == 2:     # LookUpSmall
            self.state.pitch_deg -= self.pitch_step
            self.state.pitch_deg = max(-90.0, self.state.pitch_deg)
        elif action == 3:     # LookDownSmall
            self.state.pitch_deg += self.pitch_step
            self.state.pitch_deg = min(90.0, self.state.pitch_deg)
        elif action == 4:     # MoveForward
            self.state.v_forward = 1.0
        elif action == 5:     # Stop
            self.state.v_forward = 0.0
        elif action == 6:     # Jump (no-op in v1 mock)
            pass

        # Normalize yaw into [-180, 180)
        if self.state.yaw_deg >= 180.0 or self.state.yaw_deg < -180.0:
            self.state.yaw_deg = ((self.state.yaw_deg + 180.0) % 360.0) - 180.0

    def _simulate_dynamics(self):
        # Simple 2D kinematics with forward velocity capped and slight damping
        v = max(0.0, min(self.state.v_forward, 1.0))
        v *= self.max_speed
        yaw_rad = math.radians(self.state.yaw_deg)
        dx = math.cos(yaw_rad) * v
        dz = math.sin(yaw_rad) * v
        self.state.x += dx
        self.state.z += dz

        # friction to avoid infinite glide when Stop action used
        self.state.v_forward *= 0.85

    def _encode_obs(self, s: MockState, goal: np.ndarray, stuck_flag: bool) -> np.ndarray:
        dx = goal[0] - s.x
        dz = goal[2] - s.z
        dy = goal[1] - s.y  # zero in v1

        # clip
        dx_c = float(np.clip(dx, -self.max_range, self.max_range))
        dz_c = float(np.clip(dz, -self.max_range, self.max_range))
        dy_c = float(np.clip(dy, -self.max_height, self.max_height))

        dist = math.hypot(dx, dz)
        dist_c = min(dist, self.max_range)

        # normalized
        def nz_div(a, b): 
            return a / b if b != 0 else 0.0

        dx_norm = nz_div(dx_c, self.max_range)
        dz_norm = nz_div(dz_c, self.max_range)
        dy_norm = nz_div(dy_c, self.max_height)
        horiz_dist_norm = nz_div(dist_c, self.max_range)

        sin_yaw = math.sin(math.radians(s.yaw_deg))
        cos_yaw = math.cos(math.radians(s.yaw_deg))
        pitch_norm = np.clip(s.pitch_deg / 90.0, -1.0, 1.0)

        # speed estimate approximated by forward component magnitude / max_speed
        speed_norm = np.clip(abs(self.state.v_forward) * self.max_speed / self.max_speed, 0.0, 1.0)

        obs = np.array([
            dx_norm,
            dz_norm,
            dy_norm,
            horiz_dist_norm,
            sin_yaw,
            cos_yaw,
            float(pitch_norm),
            1.0 if s.on_ground else 0.0,
            float(speed_norm),
            1.0 if stuck_flag else 0.0,
        ], dtype=np.float32)
        return obs

    @staticmethod
    def _horiz_dist(s: MockState, goal: np.ndarray) -> float:
        return math.hypot(goal[0] - s.x, goal[2] - s.z)

    def sanity_check(self, verbose: bool = True):
        """Runs a quick check to verify env API and reward logic."""
        obs, info = self.reset()
        assert isinstance(obs, np.ndarray), "Observation should be a numpy array"
        assert obs.shape == (10,), f"Obs shape mismatch: {obs.shape}"
        assert hasattr(self, "action_space"), "Missing action_space"
        assert hasattr(self, "observation_space"), "Missing observation_space"
        assert self.action_space.n == 7, "Action space should be Discrete(7)"
        total_reward = 0.0
        steps = 0
        done = False
        while not done and steps < 10:
            action = self.action_space.sample() if hasattr(self.action_space, "sample") else 0
            obs, reward, done, _, info = self.step(action)
            total_reward += reward
            steps += 1
            if verbose:
                print(f"Step {steps}: action={action}, reward={reward:.3f}, done={done}, info={info}")
        print(f"Sanity check finished: steps={steps}, total_reward={total_reward:.2f}")

    def log_episode(self, log_path: str, episode_info: dict):
        """Append episode summary to a CSV log file."""
        file_exists = os.path.isfile(log_path)
        with open(log_path, "a", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=list(episode_info.keys()))
            if not file_exists:
                writer.writeheader()
            writer.writerow(episode_info)

    # Example usage after an episode:
    # info = {
    #     "episode": ep_num,
    #     "steps": steps,
    #     "total_reward": total_reward,
    #     "goal_reached": goal_reached,
    # }
    # env.log_episode("episode_log.csv", info)


__all__ = ["MockMinecraftEnv", "DEFAULT_CFG"]
