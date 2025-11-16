"""
Survival Minecraft-Style Mock Environment
- Health & Hunger
- Food items
- Resource (gear) progression
- Hazards (lava/cactus)
- Survival reward system
- SUCCESS: survive a "Minecraft week" (compressed)
"""

from __future__ import annotations
import math, random
from dataclasses import dataclass
from collections import deque
from typing import Optional, Dict, Any
import numpy as np
import gymnasium as gym
from gymnasium import spaces


# ============================================
# CONFIG STRUCTS
# ============================================

@dataclass
class EnvConfig:
    # Time settings
    action_repeat: int = 4
    max_steps: int = 2000            # episode hard cap
    minecraft_week_steps: int = 2000 # survive this many steps = success

    # movement
    turn_delta_deg: float = 6.0
    move_speed: float = 0.25
    stop_damping: float = 0.8

    # survival stats
    max_health: int = 100
    max_hunger: int = 100
    # Slightly easier survival than before
    hunger_drain: float = 0.05
    starvation_damage: float = 0.10

    # stuck detection (not used heavily yet)
    stuck_window: int = 25
    stuck_epsilon: float = 0.03

    # world randomization / size
    world_size: float = 12.0

    # detection radii
    pickup_radius: float = 1.0


@dataclass
class RewardConfig:
    # survival rewards
    time_alive: float = 0.10
    food_reward: float = 5.0
    health_penalty: float = -0.2
    hunger_penalty: float = -0.1
    death_penalty: float = -50.0

    # gear progression
    wood_reward: float = 3.0
    stone_reward: float = 6.0
    iron_reward: float = 10.0
    diamond_reward: float = 20.0

    # environmental
    hazard_damage: float = -5.0   # less harsh than -10 to make success possible
    wall_penalty: float = -2.0
    week_bonus: float = 50.0      # big bonus for surviving a "week"
    step_penalty: float = -0.01


# ============================================
# ENVIRONMENT CLASS
# ============================================

class MockMinecraftEnv(gym.Env):
    metadata = {"render_modes": ["ansi"], "render_fps": 20}

    def __init__(self, cfg=None, rew=None):
        super().__init__()
        self.cfg = cfg or EnvConfig()
        self.rew = rew or RewardConfig()

        # action space: turn L/R, forward, stop, (+3 spare)
        self.action_space = spaces.Discrete(7)

        # observation: dx, dz, dist, sin(yaw), cos(yaw),
        #              health_norm, hunger_norm, speed, gear_level
        self.observation_space = spaces.Box(
            low=-np.inf, high=np.inf, shape=(9,), dtype=np.float32
        )

        # RNG and distance history
        self._rng = np.random.default_rng()
        self._last_dists = deque(maxlen=self.cfg.stuck_window)

        # world structures
        self.walls = [
            (2, -3, 4, 3),
            (-4, 5, -2, 10),
            (6, 6, 9, 9),
        ]

        self.hazards = [
            (-2, -2, -1, -1),  # lava
            (3, 8, 4, 9),      # cactus
        ]

        self.resources = []   # filled on reset
        self.food = []        # filled on reset

    # ============================================
    # RESET
    # ============================================

    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)
        random.seed(seed)

        # player initial state
        self.x = self._rng.uniform(-2, 2)
        self.z = self._rng.uniform(-2, 2)
        self.yaw = self._rng.uniform(-math.pi, math.pi)
        self.vx = self.vz = 0.0

        self.health = self.cfg.max_health
        self.hunger = self.cfg.max_hunger
        self.gear_level = 0   # 0=none, 1=wood, 2=stone, 3=iron, 4=diamond

        self._step_count = 0
        self._last_dists.clear()

        # food placements (more + stronger to make survival realistic)
        self.food = [
            {
                "x": self._rng.uniform(-self.cfg.world_size, self.cfg.world_size),
                "z": self._rng.uniform(-self.cfg.world_size, self.cfg.world_size),
                "value": 40,  # more hunger restore
                "taken": False,
            }
            for _ in range(8)
        ]

        # resource nodes: wood → stone → iron → diamond
        self.resources = [
            {"tier": 1, "x": -8, "z": 3, "taken": False},
            {"tier": 2, "x": 7, "z": -4, "taken": False},
            {"tier": 3, "x": -5, "z": -8, "taken": False},
            {"tier": 4, "x": 9, "z": 8, "taken": False},
        ]

        obs = self._get_obs()
        info = {"success": False}
        return obs, info

    # ============================================
    # STEP
    # ============================================

    def step(self, action: int):
        total_reward = 0.0
        term = trunc = False
        info: Dict[str, Any] = {"success": False}

        for _ in range(self.cfg.action_repeat):
            self._apply_action(action)
            collided = self._physics_tick()

            # survival systems (hunger, health, time-alive reward)
            surv_r, died = self._survival_tick()
            total_reward += surv_r

            if died:
                # death ends the episode, not a success
                term = True
                return self._get_obs(), total_reward, term, trunc, info

            # pickups: food + gear
            gather_r = self._handle_pickups()
            total_reward += gather_r

            # walls
            if collided:
                total_reward += self.rew.wall_penalty

            # hazards
            if self._in_hazard():
                self.health -= 5
                total_reward += self.rew.hazard_damage

            self._step_count += 1

            # SUCCESS CONDITION: survived a "Minecraft week"
            if self._step_count >= self.cfg.minecraft_week_steps:
                info["success"] = True
                total_reward += self.rew.week_bonus
                term = True
                return self._get_obs(), total_reward, term, trunc, info

            # hard episode cap
            if self._step_count >= self.cfg.max_steps:
                trunc = True
                break

        # small step penalty at end of aggregated action
        total_reward += self.rew.step_penalty

        obs = self._get_obs()
        return obs, total_reward, term, trunc, info

    # ============================================
    # ACTIONS
    # ============================================

    def _apply_action(self, a: int):
        turn = math.radians(self.cfg.turn_delta_deg)

        if a == 0:      # turn left
            self.yaw -= turn
        elif a == 1:    # turn right
            self.yaw += turn
        elif a == 2:    # move forward
            self.vx += math.cos(self.yaw) * self.cfg.move_speed
            self.vz += math.sin(self.yaw) * self.cfg.move_speed
        elif a == 3:    # stop / damp
            self.vx *= self.cfg.stop_damping
            self.vz *= self.cfg.stop_damping
        # actions 4,5,6 reserved for future (jump, attack, etc.)

    # ============================================
    # PHYSICS + WALLS
    # ============================================

    def _physics_tick(self):
        old_x, old_z = self.x, self.z
        self.x += self.vx
        self.z += self.vz

        collided = False
        for (x1, z1, x2, z2) in self.walls:
            if x1 <= self.x <= x2 and z1 <= self.z <= z2:
                # revert and zero velocity
                self.x, self.z = old_x, old_z
                self.vx = self.vz = 0.0
                collided = True
                break

        # friction
        self.vx *= 0.98
        self.vz *= 0.98
        return collided

    # ============================================
    # SURVIVAL TICK
    # ============================================

    def _survival_tick(self):
        """Handle hunger drain, starvation damage, and alive reward."""
        reward = 0.0

        # hunger drains
        self.hunger -= self.cfg.hunger_drain
        reward += self.rew.hunger_penalty * self.cfg.hunger_drain

        # starving → health damage
        if self.hunger <= 0:
            self.health -= self.cfg.starvation_damage
            reward += self.rew.health_penalty * self.cfg.starvation_damage

        # reward for being alive at all
        reward += self.rew.time_alive

        # death?
        if self.health <= 0:
            reward += self.rew.death_penalty
            return reward, True

        return reward, False

    # ============================================
    # PICKUPS
    # ============================================

    def _handle_pickups(self):
        total = 0.0

        # food
        for item in self.food:
            if item["taken"]:
                continue
            if self._dist(self.x, self.z, item["x"], item["z"]) < self.cfg.pickup_radius:
                item["taken"] = True
                self.hunger = min(self.cfg.max_hunger, self.hunger + item["value"])
                total += self.rew.food_reward

        # resources (gear progression)
        for node in self.resources:
            if node["taken"]:
                continue
            if self._dist(self.x, self.z, node["x"], node["z"]) < self.cfg.pickup_radius:
                node["taken"] = True
                tier = node["tier"]

                if tier > self.gear_level:
                    self.gear_level = tier
                    if tier == 1:
                        total += self.rew.wood_reward
                    elif tier == 2:
                        total += self.rew.stone_reward
                    elif tier == 3:
                        total += self.rew.iron_reward
                    elif tier == 4:
                        total += self.rew.diamond_reward

        return total

    # ============================================
    # HAZARDS
    # ============================================

    def _in_hazard(self):
        for (x1, z1, x2, z2) in self.hazards:
            if x1 <= self.x <= x2 and z1 <= self.z <= z2:
                return True
        return False

    # ============================================
    # OBSERVATION
    # ============================================

    def _get_obs(self):
        # "Goal" for direction is the diamond node (tier 4)
        diamond = next(r for r in self.resources if r["tier"] == 4)
        dx = diamond["x"] - self.x
        dz = diamond["z"] - self.z
        dist = math.sqrt(dx * dx + dz * dz)
        speed = math.sqrt(self.vx ** 2 + self.vz ** 2)

        obs = np.array(
            [
                dx,
                dz,
                dist,
                math.sin(self.yaw),
                math.cos(self.yaw),
                self.health / self.cfg.max_health,
                self.hunger / self.cfg.max_hunger,
                speed,
                float(self.gear_level),
            ],
            dtype=np.float32,
        )

        self._last_dists.append(dist)
        return obs

    # ============================================
    @staticmethod
    def _dist(x1, z1, x2, z2):
        return math.sqrt((x2 - x1) ** 2 + (z2 - z1) ** 2)
