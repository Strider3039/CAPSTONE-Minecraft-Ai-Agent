from __future__ import annotations
import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim

from ai.src.policy.base import Policy
from ai.src.policy.rl.dqn.model import QNetwork
from ai.src.policy.action_space import NUM_ACTIONS, ToMinecraftControls
from ai.src.policy.obs_encoding import OBS_DIM, EncodeObservation
from .model import QNetwork  # keep for relative imports
from .replay import ReplayBuffer
from .reward_engine import RewardEngine


def EpsilonGreedyAction(
    qNet: QNetwork,
    obsVec: np.ndarray,
    epsilon: float,
    device: str = "cpu",
) -> int:
    """
    Select an action index using epsilon-greedy strategy.

    - With probability epsilon: random action.
    - Otherwise: argmax_a Q(s, a).
    """
    if np.random.rand() < epsilon:
        return np.random.randint(0, NUM_ACTIONS)

    obsTensor = torch.from_numpy(obsVec).unsqueeze(0).to(device)
    with torch.no_grad():
        qValues = qNet(obsTensor)

    return int(torch.argmax(qValues, dim=1).item())


class DQNAgent:
    """
    Training-side DQN agent.
    """

    def __init__(
        self,
        gamma: float = .99,
        lr: float = 1e-3,
        bufferCapacity: int = 100_000,
        batchSize: int = 64,
        device: str = "cpu",
        minReplaySize: int = 1_000,
        targetUpdateFreq: int = 1_000,
        epsilonStart: float = 1.0,
        epsilonEnd: float = 0.1,
        epsilonDecay: int = 100_000,
        hiddenDims=(128, 128),
    ) -> None:
        self.gamma = gamma
        self.batchSize = batchSize
        self.device = device
        self.minReplaySize = minReplaySize
        self.targetUpdateFreq = targetUpdateFreq

        # Epsilon Schedule
        self.epsilonStart = epsilonStart
        self.epsilonEnd = epsilonEnd
        self.epsilonDecay = epsilonDecay
        self.totalSteps = 0

        # Networks
        self.qNet = QNetwork(OBS_DIM, NUM_ACTIONS, hiddenDims).to(device)
        self.targetNet = QNetwork(OBS_DIM, NUM_ACTIONS, hiddenDims).to(device)
        self.targetNet.load_state_dict(self.qNet.state_dict())
        self.targetNet.eval()

        # Optimizer
        self.optimizer = optim.Adam(self.qNet.parameters(), lr=lr)

        # Replay Buffer
        self.replayBuffer = ReplayBuffer(bufferCapacity)

    def CurrentEpsilon(self) -> float:
        frac = min(float(self.totalSteps) / self.epsilonDecay, 1.0)
        return self.epsilonStart + frac * (self.epsilonEnd - self.epsilonStart)

    def SelectAction(self, obsVec: np.ndarray) -> int:
        eps = self.CurrentEpsilon()
        actionIdx = EpsilonGreedyAction(self.qNet, obsVec, eps, self.device)
        return actionIdx

    def StoreTransition(
        self,
        state: np.ndarray,
        action: int,
        reward: float,
        nextState: np.ndarray,
        done: bool,
    ) -> None:
        self.replayBuffer.Add(state, action, reward, nextState, done)

    def TrainStep(self) -> float | None:
        if len(self.replayBuffer) < self.minReplaySize:
            return None

        states, actions, rewards, nextStates, dones = self.replayBuffer.Sample(self.batchSize)

        statesTensor = torch.from_numpy(states).to(self.device)
        actionTensor = torch.from_numpy(actions).long().to(self.device)
        rewardTensor = torch.from_numpy(rewards).to(self.device)
        nextStateTensor = torch.from_numpy(nextStates).to(self.device)
        doneTensor = torch.from_numpy(dones).to(self.device)

        # Q(s, a)
        qValues = self.qNet(statesTensor)
        qSA = qValues.gather(1, actionTensor.unsqueeze(1)).squeeze(1)

        # max_a' Q_target(s', a')
        with torch.no_grad():
            qNext = self.targetNet(nextStateTensor)
            maxQNext, _ = torch.max(qNext, dim=1)

        targets = rewardTensor + self.gamma * maxQNext * (1 - doneTensor)

        loss = nn.functional.mse_loss(qSA, targets)

        self.optimizer.zero_grad()
        loss.backward()
        self.optimizer.step()

        return loss.item()

    def UpdateTargetNetwork(self) -> None:
        if self.totalSteps % self.targetUpdateFreq == 0:
            self.targetNet.load_state_dict(self.qNet.state_dict())

    def IncrementStep(self) -> None:
        self.totalSteps += 1

    def Save(self, path: str) -> None:
        torch.save(
            {
                "qNet": self.qNet.state_dict(),
                "qTargetNet": self.targetNet.state_dict(),
                "optimizer": self.optimizer.state_dict(),
                "totalSteps": self.totalSteps,
            },
            path,
        )

    def Load(self, path: str, mapLocation: str = "cpu") -> None:
        checkpoint = torch.load(path, map_location=mapLocation)
        self.qNet.load_state_dict(checkpoint["qNet"])
        self.targetNet.load_state_dict(checkpoint["qTargetNet"])
        self.optimizer.load_state_dict(checkpoint["optimizer"])
        self.totalSteps = checkpoint["totalSteps"]


class DQNPolicy(Policy):
    """
    Inference-only policy for a fixed trained model.
    """

    def __init__(
        self,
        model: QNetwork,
        max_ray_dist: float,
        device: str = "cpu",
    ) -> None:
        self.model = model.to(device)
        self.model.eval()
        self.max_ray_dist = max_ray_dist
        self.device = device

        self.seq = 0  # for Forge

    @classmethod
    def FromCheckpoint(
        cls,
        checkpoint_path: str,
        max_ray_dist: float,
        device: str = "cpu",
        hidden_sizes=(128, 128),
    ) -> "DQNPolicy":
        model = QNetwork(OBS_DIM, NUM_ACTIONS, hidden_sizes)
        ckpt = torch.load(checkpoint_path, map_location=device)
        if isinstance(ckpt, dict) and "qNet" in ckpt:
            model.load_state_dict(ckpt["qNet"])
        else:
            model.load_state_dict(ckpt)
        return cls(model, max_ray_dist, device)

    def act(self, obsMsg: dict) -> dict:
        obsVec = EncodeObservation(obsMsg, self.max_ray_dist)
        obsTensor = torch.from_numpy(obsVec).unsqueeze(0).to(self.device)

        with torch.no_grad():
            qValues = self.model(obsTensor)
            actionIdx = int(torch.argmax(qValues, dim=1).item())

        self.seq += 1
        return ToMinecraftControls(actionIdx, self.seq)


class OnlineDQNPolicy(Policy):
    """
    Online RL policy that learns in Minecraft using DQNAgent and a multi-phase RewardEngine.

    - Computes reward from consecutive observations via RewardEngine.
    - Logs episodic return + current curriculum phase when an episode ends.
    - Stores transitions into replay buffer and trains the DQN.
    """

    def __init__(
        self,
        agent: DQNAgent,
        max_ray_dist: float,
        device: str = "cpu",
    ) -> None:
        self.agent = agent
        self.max_ray_dist = max_ray_dist
        self.device = device

        # For Forge / bridge (monotonic action sequence id)
        self.seq = 0

        # Last transition pieces for (s, a, r, s')
        self._last_obs_vec: np.ndarray | None = None
        self._last_action_idx: int | None = None
        self._last_obs_msg: dict | None = None

        # Multi-phase curriculum reward engine
        self.reward_engine = RewardEngine()

        # Episode tracking
        self.episode_idx = 0

    @classmethod
    def FromCheckpoint(
        cls,
        checkpoint_path: str | None,
        max_ray_dist: float,
        device: str = "cpu",
        hidden_sizes=(128, 128),
    ) -> "OnlineDQNPolicy":
        """
        Build an OnlineDQNPolicy with a training-capable DQNAgent.

        If checkpoint_path is provided and exists, warm-start from it.
        Otherwise start from scratch.
        """
        agent = DQNAgent(
            device=device,
            hiddenDims=hidden_sizes,
        )

        if checkpoint_path:
            try:
                agent.Load(checkpoint_path, mapLocation=device)
                print(f"[OnlineDQNPolicy] Loaded checkpoint: {checkpoint_path}")
            except FileNotFoundError:
                print(
                    f"[OnlineDQNPolicy] checkpoint not found: {checkpoint_path}, training from scratch"
                )

        return cls(agent, max_ray_dist, device)

    # ----- reward shaping utility -----

    def _compute_reward(
        self,
        prev_obs: dict | None,
        curr_obs: dict,
    ) -> tuple[float, bool]:
        """
        Delegate reward computation to RewardEngine (multi-phase curriculum).
        """
        return self.reward_engine.compute(prev_obs, curr_obs)

    def act(self, obsMsg: dict) -> dict:
        """
        Main hook called by PolicyWorker every tick.

        Flow:
          1. Encode current observation → obs_vec.
          2. If we have a previous (s, a), compute reward and store transition.
          3. Train DQNAgent from replay (if enough samples).
          4. If episode ended (done=True), log return + phase and reset episode-level state.
          5. Select next action via epsilon-greedy.
          6. Return Minecraft controls dict (with seq id).
        """
        # 1) Encode current observation
        obs_vec = EncodeObservation(obsMsg, self.max_ray_dist)

        # 2) If we have a previous step, compute reward & train
        if self._last_obs_vec is not None and self._last_action_idx is not None:
            reward, done = self._compute_reward(
                prev_obs=self._last_obs_msg,
                curr_obs=obsMsg,
            )

            # Store transition in replay
            self.agent.StoreTransition(
                state=self._last_obs_vec,
                action=self._last_action_idx,
                reward=reward,
                nextState=obs_vec,
                done=done,
            )

            # One gradient update (if enough data)
            loss = self.agent.TrainStep()
            if loss is not None:
                self.agent.UpdateTargetNetwork()

            # Episode end handling (from RewardEngine's "done")
            if done:
                phase = getattr(self.reward_engine, "phase", None)
                episode_return = getattr(self.reward_engine, "episode_return", None)

                msg = f"[OnlineDQN] Episode {self.episode_idx} ended."
                if episode_return is not None:
                    msg += f" return={episode_return:.2f}"
                if phase is not None:
                    msg += f" phase={phase}"
                print(msg)

                # Prepare for next episode
                self.episode_idx += 1
                self.reward_engine.reset_episode()

                # Clear transition memory so next call starts fresh
                self._last_obs_vec = None
                self._last_action_idx = None
                self._last_obs_msg = None

        # 3) Select action for *current* state
        action_idx = self.agent.SelectAction(obs_vec)
        self.agent.IncrementStep()

        # 4) Remember this (s, a) for the next tick's (s', r, done)
        self._last_obs_vec = obs_vec
        self._last_action_idx = action_idx
        self._last_obs_msg = obsMsg

        # 5) Wrap into Minecraft controls with sequential id
        self.seq += 1
        return ToMinecraftControls(action_idx, self.seq)
