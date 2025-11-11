"""
Mock Minecraft Gymnasium Environment (v1)
- Discrete(7) actions
- 10D observation vector
- Reward shaping and episode logic aligned with the design spec
"""

from __future__ import annotations
import math
import random
from dataclasses import dataclass
from typing import Dict, Optional, Any

import numpy as np

# Optional dependency: Gymnasium. We provide a tiny fallback so this file can import without it.
try:
    import gymnasium as gym
    from gymnasium import spaces
except Exception:  # pragma: no cover
    class _Dummy:
        def __getattr__(self, k):
            # IMPORTANT: return the *type*, not an instance, so subclassing works.
            return object
    gym = _Dummy()

    class _Spaces:
        class Box: ...
        class Discrete: ...
    spaces = _Spaces()


# -------------------------
# Tunables (shared defaults)
# -------------------------

DEFAULT_CFG = {
    "tick_hz": 20,              # sim tick rate (informational in mock)
    "action_repeat_k": 4,       # apply same action for k sub-steps per env.step
    "max_steps": 600,           # episode length cap (env steps, not sub-steps)
    "goal_radius": 1.5,         # success threshold on horizontal distance
    "max_range": 40.0,          # for clipping/normalizing dx,dz,dist
    "max_height": 20.0,         # for dy normalization (mock uses dy=0)
    "max_speed": 0.2,           # blocks/tick; governs forward motion scale
    "start_yaw_random": False,  # randomize yaw at reset
    "goal_bounds": {            # sampling bounds for goal
        "min_xy": -15.0,
        "max_xy":  15.0,
        "min_dist": 5.0,        # min distance from start
    },
    "reward": {
        "progress_alpha": 1.0,  # weight on (prev_dist - new_dist)
        "step_penalty":  -0.01, # small time penalty
        "stuck_penalty":  -5.0, # fired when slow progress persists
        "death_penalty": -20.0, # unused in v1 mock (no hazards)
        "goal_bonus":    100.0, # big terminal reward
        "stuck_speed_thresh": 0.02, # avg progress/k below this counts as "slow"
        "stuck_ticks": 15,          # consecutive slow checks → penalty
    },
    "action_magnitudes": {
        "look_yaw_deg":   8.0,  # yaw delta per sub-step
        "look_pitch_deg": 4.0,  # pitch delta per sub-step (no dynamics impact in v1)
        "move_forward":   1,    # forward input flag (0/1) in mock
        "jump":           True, # no-op in v1 mock, kept for parity
    },
}


@dataclass
class MockState:
    # 2.5D pose (y present for parity; dynamics are 2D: x,z only)
    x: float = 0.0
    y: float = 0.0
    z: float = 0.0
    yaw_deg: float = 0.0
    pitch_deg: float = 0.0
    v_forward: float = 0.0
    on_ground: bool = True

    # bookkeeping
    step_index: int = 0            # env steps * k sub-steps
    stuck_counter: int = 0
    last_progress: float = 0.0


class MockMinecraftEnv(getattr(gym, "Env", object)):
    """
    Gymnasium-style mock environment for rapid DQN iteration.

    Observation (10 dims, fixed order):
        0 dx_norm            ∈ [-1, 1]   (dx clipped to ±max_range, then /max_range)
        1 dz_norm            ∈ [-1, 1]
        2 dy_norm            ∈ [-1, 1]   (always 0 in v1 mock)
        3 horiz_dist_norm    ∈ [0, 1]    (min(hypot(dx,dz), max_range)/max_range)
        4 sin_yaw            ∈ [-1, 1]
        5 cos_yaw            ∈ [-1, 1]
        6 pitch_norm         ∈ [-1, 1]   (pitch/90)
        7 on_ground          ∈ {0, 1}
        8 speed_norm         ∈ [0, 1]    (|v_forward| normalized)
        9 stuck_flag         ∈ {0, 1}    (1 only on the step a stuck penalty fires)

    Action space: Discrete(7)
        0: TurnLeftSmall    (yaw -= look_yaw_deg)
        1: TurnRightSmall   (yaw += look_yaw_deg)
        2: LookUpSmall      (pitch -= look_pitch_deg)   # no motion effect
        3: LookDownSmall    (pitch += look_pitch_deg)   # no motion effect
        4: MoveForward      (v_forward = 1)
        5: Stop             (v_forward = 0)
        6: Jump             (no-op in v1 mock; kept for parity)

    Step executes k sub-steps, then computes reward/termination on the k-aggregated transition.
    """

    metadata = {"render_modes": []}

    def __init__(self, cfg: Optional[Dict[str, Any]] = None, seed: Optional[int] = None):
        # merge cfg (shallow + nested dicts)
        self.cfg = DEFAULT_CFG.copy()
        if cfg:
            for k, v in cfg.items():
                if k in self.cfg and isinstance(self.cfg[k], dict) and isinstance(v, dict):
                    self.cfg[k].update(v)
                else:
                    self.cfg[k] = v

        self.rng = random.Random(seed)
        self.np_rng = np.random.default_rng(seed)

        # Spaces (created only if Gym is available)
        low = np.array([-1, -1, -1, 0, -1, -1, -1, 0, 0, 0], dtype=np.float32)
        high = np.array([ 1,  1,  1, 1,  1,  1,  1, 1, 1, 1], dtype=np.float32)
        try:
            self.observation_space = spaces.Box(low=low, high=high, dtype=np.float32)
            self.action_space = spaces.Discrete(7)
        except Exception:  # pragma: no cover
            self.observation_space = None
            self.action_space = None

        # cache cfg
        self.k = int(self.cfg["action_repeat_k"])
        self.max_steps = int(self.cfg["max_steps"])
        self.max_speed = float(self.cfg["max_speed"])
        self.max_range = float(self.cfg["max_range"])
        self.max_height = float(self.cfg["max_height"])
        self.goal_radius = float(self.cfg["goal_radius"])

        rwd = self.cfg["reward"]
        self.alpha = float(rwd["progress_alpha"])
        self.step_pen = float(rwd["step_penalty"])
        self.stuck_pen = float(rwd["stuck_penalty"])
        self.goal_bonus = float(rwd["goal_bonus"])
        self.stuck_speed_thresh = float(rwd["stuck_speed_thresh"])
        self.stuck_ticks = int(rwd["stuck_ticks"])

        mags = self.cfg["action_magnitudes"]
        self.yaw_step = float(mags["look_yaw_deg"])
        self.pitch_step = float(mags["look_pitch_deg"])

        # state
        self.state = MockState()
        self.goal = np.zeros(3, dtype=np.float32)
        self.prev_dist: float = 0.0
        self.done: bool = False

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
            raise RuntimeError("Episode is done. Call reset().")

        # Apply chosen action for k sub-steps
        stuck_triggered = False
        for _ in range(self.k):
            self._apply_action(action)
            self._simulate_dynamics()
            self.state.step_index += 1

        # Compute progress/reward
        new_dist = self._horiz_dist(self.state, self.goal)
        progress = self.prev_dist - new_dist
        reward = self.alpha * progress + self.step_pen

        # Stuck detection: average progress per sub-step
        avg_progress = abs(progress) / max(self.k, 1)
        if avg_progress < self.stuck_speed_thresh:
            self.state.stuck_counter += 1
            if self.state.stuck_counter >= self.stuck_ticks:
                reward += self.stuck_pen
                stuck_triggered = True
                self.state.stuck_counter = 0
        else:
            self.state.stuck_counter = 0

        # Goal check
        goal_reached = new_dist < self.goal_radius
        if goal_reached:
            reward += self.goal_bonus
            self.done = True

        # Max steps
        if self.state.step_index >= self.max_steps:
            self.done = True

        obs = self._encode_obs(self.state, self.goal, stuck_flag=stuck_triggered)
        info = {
            "raw_dist": float(new_dist),
            "progress": float(progress),
            "stuck_triggered": bool(stuck_triggered),
            "goal_reached": bool(goal_reached),
            "step_index": int(self.state.step_index),
        }
        self.prev_dist = new_dist

        # Gymnasium API: (obs, reward, terminated, truncated, info)
        return obs, float(reward), bool(self.done), False, info

    # ---------------
    # Helpers
    # ---------------
    def _sample_goal(self):
        b = self.cfg["goal_bounds"]
        min_xy, max_xy, min_dist = float(b["min_xy"]), float(b["max_xy"]), float(b["min_dist"])
        while True:
            gx = self.rng.uniform(min_xy, max_xy)
            gz = self.rng.uniform(min_xy, max_xy)
            if math.hypot(gx - self.state.x, gz - self.state.z) >= min_dist:
                break
        self.goal = np.array([gx, 0.0, gz], dtype=np.float32)

    def _apply_action(self, action: int):
        if action == 0:      # TurnLeftSmall
            self.state.yaw_deg -= self.yaw_step
        elif action == 1:    # TurnRightSmall
            self.state.yaw_deg += self.yaw_step
        elif action == 2:    # LookUpSmall
            self.state.pitch_deg = max(-90.0, self.state.pitch_deg - self.pitch_step)
        elif action == 3:    # LookDownSmall
            self.state.pitch_deg = min( 90.0, self.state.pitch_deg + self.pitch_step)
        elif action == 4:    # MoveForward
            self.state.v_forward = 1.0
        elif action == 5:    # Stop
            self.state.v_forward = 0.0
        elif action == 6:    # Jump (no-op in v1 mock)
            pass

        # normalize yaw into [-180, 180)
        if self.state.yaw_deg >= 180.0 or self.state.yaw_deg < -180.0:
            self.state.yaw_deg = ((self.state.yaw_deg + 180.0) % 360.0) - 180.0

    def _simulate_dynamics(self):
        # Simple 2D forward motion with damping
        v = max(0.0, min(self.state.v_forward, 1.0)) * self.max_speed
        yaw_rad = math.radians(self.state.yaw_deg)
        self.state.x += math.cos(yaw_rad) * v
        self.state.z += math.sin(yaw_rad) * v
        # friction on forward intent
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

        # normalize
        def nz_div(a, b): return a / b if b != 0 else 0.0
        dx_norm = nz_div(dx_c, self.max_range)
        dz_norm = nz_div(dz_c, self.max_range)
        dy_norm = nz_div(dy_c, self.max_height)
        horiz_dist_norm = nz_div(dist_c, self.max_range)

        sin_yaw = math.sin(math.radians(s.yaw_deg))
        cos_yaw = math.cos(math.radians(s.yaw_deg))
        pitch_norm = float(np.clip(s.pitch_deg / 90.0, -1.0, 1.0))

        # speed approx: |forward-intent| normalized
        speed_norm = float(np.clip(abs(self.state.v_forward), 0.0, 1.0))

        obs = np.array([
            dx_norm,
            dz_norm,
            dy_norm,
            horiz_dist_norm,
            sin_yaw,
            cos_yaw,
            pitch_norm,
            1.0 if s.on_ground else 0.0,
            speed_norm,
            1.0 if stuck_flag else 0.0,
        ], dtype=np.float32)
        return obs

    @staticmethod
    def _horiz_dist(s: MockState, goal: np.ndarray) -> float:
        return math.hypot(goal[0] - s.x, goal[2] - s.z)
