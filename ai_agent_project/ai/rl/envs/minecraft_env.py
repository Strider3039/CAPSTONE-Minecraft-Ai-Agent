import asyncio
from typing import Tuple, Any, Dict

from ai.src.policy.obs_encoding import encode_observation
from ai.src.policy.action_space import MinecraftActionSpace


class MinecraftEnv:
    """
    Gym-like environment for Minecraft via the AI Bridge.
    Handles:
        - reset()
        - step()
        - reward shaping
        - waiting for episode_start / episode_end
    """

    def __init__(self, bridge, reward_config: Dict[str, Any] = None):
        self.bridge = bridge
        self.action_space = MinecraftActionSpace()
        self.last_obs_vec = None
        self.last_obs_msg = None
        self.episode_step = 0
        self.episode_reward = 0

        self.reward_cfg = reward_config or {
            "alive_reward": 0.01,
            "step_penalty": 0.0,
            "collision_penalty": -0.03,
            "move_reward": 0.002,
            "death_penalty": -10,
            "void_penalty": -10,
            "timeout_penalty": -1,
        }

    # ----------------------------------------------------------------------
    # RESET
    # ----------------------------------------------------------------------
    async def reset(self):
        """
        Reset the whole episode in Minecraft.
        This triggers Forge to teleport, respawn, clear inventory, etc.
        """

        await self.bridge.send_command("reset_episode")

        first_obs = None

        while True:
            msg = await self.bridge.recv()

            kind = msg.get("kind")

            if kind == "episode_start":
                # next observation will be fresh after teleport
                continue

            if kind == "observation":
                first_obs = msg
                break

        self.last_obs_msg = first_obs
        self.last_obs_vec = encode_observation(first_obs["payload"])
        self.episode_step = 0
        self.episode_reward = 0

        return self.last_obs_vec

    # ----------------------------------------------------------------------
    # STEP
    # ----------------------------------------------------------------------
    async def step(self, action_idx: int) -> Tuple[Any, float, bool, Dict]:
        """
        Send one action → wait for observation or episode_end.
        """
        self.episode_step += 1

        action_payload = self.action_space.to_minecraft_controls(action_idx)
        await self.bridge.send_action(action_payload)

        reward = 0.0
        done = False
        info = {}

        while True:
            msg = await self.bridge.recv()
            kind = msg.get("kind")

            # EPISODE END
            if kind == "episode_end":
                reason = msg["payload"]["episode_end"]["reason"]
                reward += self._terminal_reward(reason)
                done = True
                info["reason"] = reason
                break

            # OBSERVATION (normal)
            if kind == "observation":
                self.last_obs_msg = msg
                self.last_obs_vec = encode_observation(msg["payload"])
                reward += self._obs_reward(msg)
                break

            # ACTION RESULT
            if kind == "action_result":
                reward += self._action_event_reward(msg)

        self.episode_reward += reward

        return self.last_obs_vec, reward, done, info

    # ----------------------------------------------------------------------
    # REWARD SHAPING
    # ----------------------------------------------------------------------
    def _action_event_reward(self, msg: dict) -> float:
        res = msg["payload"]["action_result"]
        status = res["status"]

        if status == "fail":
            return -0.05
        if status == "blocked":
            return -0.05
        if status == "timeout":
            return -0.1

        return 0.0

    def _obs_reward(self, obs_msg: dict) -> float:
        payload = obs_msg["payload"]
        reward = 0

        # alive reward
        reward += self.reward_cfg["alive_reward"]

        # encourage moving forward
        if payload.get("front_clear", True):
            reward += self.reward_cfg["move_reward"]

        # collision penalty
        if payload["collision"]["is_colliding"]:
            reward += self.reward_cfg["collision_penalty"]

        return reward

    def _terminal_reward(self, reason: str) -> float:
        cfg = self.reward_cfg

        if reason == "death":
            return cfg["death_penalty"]
        if reason == "void":
            return cfg["void_penalty"]
        if reason == "timeout":
            return cfg["timeout_penalty"]
        if reason == "goal":
            return +10

        return 0.0
