from __future__ import annotations
import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim

from ai.src.policy.base import Policy
from ai.src.policy.rl.dqn.model import QNetwork
from ai.src.policy.action_space import NUM_ACTIONS, ToMinecraftControls
from ai.src.policy.obs_encoding import OBS_DIM, EncodeObservation
from .model import QNetwork
from .replay import ReplayBuffer

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

    Owns
    - Q-network and target network
    - Replay buffer
    - Epsilon-greedy action selection
    - optimizer for Q-network

    Works on observation vectors.
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
        """
        Linear decay from epsilon_start to epsilon_end over epsilon_decay_steps.
        """

        frac = min(float(self.totalSteps) / self.epsilonDecay, 1.0)
        return self.epsilonStart + frac * (self.epsilonEnd - self.epsilonStart)
    
    def SelectAction(self, obsVec: np.ndarray) -> int:
        """
        Select action using epsilon-greedy strategy.
        """
        
        eps = self.CurrentEpsilon()
        actionIdx = EpsilonGreedyAction(
            self.qNet, obsVec, eps, self.device
        )
        return actionIdx
    
    def StoreTransition(
        self,
        state: np.ndarray,
        action: int,
        reward: float,
        nextState: np.ndarray,
        done: bool,
    ) -> None:
        """
        Store transition in replay buffer.
        """

        self.replayBuffer.Add(state, action, reward, nextState, done)

    def TrainStep(self) -> float | None:
        """
        Perform one gradient update from a batch sampled from replay buffer.

        Returns:
          loss value (float) if an update was performed,
          or None if there wasn't enough data yet.
        """

        if len(self.replayBuffer) < self.minReplaySize:
            return None
        
        states, actions, rewards, nextStates, dones = self.replayBuffer.Sample(self.batchSize)

        statesTensor = torch.from_numpy(states).to(self.device)
        actionTensor = torch.from_numpy(actions).long().to(self.device)
        rewardTensor = torch.from_numpy(rewards).to(self.device)
        nextStateTensor = torch.from_numpy(nextStates).to(self.device)
        doneTensor = torch.from_numpy(dones).to(self.device)

        # Q(s, a): q values for taken actions
        qValues = self.qNet(statesTensor) # (batch, num_actions)
        qSA = qValues.gather(1, actionTensor.unsqueeze(1)).squeeze(1) # (batch,)

        # max_a' Q_target(s', a'): target q values for next states
        with torch.no_grad():
            qNext = self.targetNet(nextStateTensor) # (batch, num_actions)
            maxQNext, _ = torch.max(qNext, dim=1)   # (batch,)

        # Compute target: r + gamma * max_a' Q_target(s', a') * (1 - done)
        targets = rewardTensor + self.gamma * maxQNext * (1 - doneTensor)

        # MSE Loss
        loss = nn.functional.mse_loss(qSA, targets)

        # Gradient step
        self.optimizer.zero_grad()
        loss.backward()
        self.optimizer.step()

        return loss.item()

    def UpdateTargetNetwork(self) -> None:
        """
        Update target network parameters.
        """

        if self.totalSteps % self.targetUpdateFreq == 0:
            self.targetNet.load_state_dict(self.qNet.state_dict())

    def IncrementStep(self) -> None:
        """
        Increment total steps counter.
        """

        self.totalSteps += 1

    def Save(self, path: str) -> None:
        """
        Save Q-network parameters to file.
        """

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
        """
        Load Q-network parameters from file.
        """

        checkpoint = torch.load(path, map_location=mapLocation)
        self.qNet.load_state_dict(checkpoint["qNet"])
        self.targetNet.load_state_dict(checkpoint["qTargetNet"])
        self.optimizer.load_state_dict(checkpoint["optimizer"])
        self.totalSteps = checkpoint["totalSteps"]


class DQNPolicy(Policy):
    """
    Inference-time policy for live AI control.
    Loads a trained Q-network and outputs Minecraft actions.
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

        # NEW: add action sequencing
        self.seq = 0

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
        """
        Convert observation → encoded vector → Q-network → action payload.
        """

        # Encode obs
        obsVec = EncodeObservation(obsMsg, self.max_ray_dist)
        obsTensor = torch.from_numpy(obsVec).unsqueeze(0).to(self.device)

        # Choose best action
        with torch.no_grad():
            qValues = self.model(obsTensor)
            actionIdx = int(torch.argmax(qValues, dim=1).item())

        # NEW: increment sequence counter
        self.seq += 1

        # Send with sequence ID (required by Forge bridge)
        return ToMinecraftControls(actionIdx, self.seq)

class OnlineDQNPolicy(Policy):
    """
    Online RL policy that *learns in Minecraft* using DQNAgent.

    - Keeps a DQNAgent instance (with replay buffer, target net, etc.).
    - On each obs: trains from previous transition (if any), then picks next action.
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

        # for sequencing (Forge likes to see a monotonic ID)
        self.seq = 0

        # last transition pieces
        self._last_obs_vec: np.ndarray | None = None
        self._last_action_idx: int | None = None
        self._last_done: bool = False

    @classmethod
    def FromCheckpoint(
        cls,
        checkpoint_path: str | None,
        max_ray_dist: float,
        device: str = "cpu",
        hidden_sizes=(128, 128),
    ) -> "OnlineDQNPolicy":
        # Build DQNAgent (training-capable)
        agent = DQNAgent(
            device=device,
            hiddenDims=hidden_sizes,
        )

        # If we have a checkpoint, warm-start from it (optional)
        if checkpoint_path:
            try:
                agent.Load(checkpoint_path, mapLocation=device)
            except FileNotFoundError:
                # Start from scratch if file missing
                print(f"[OnlineDQNPolicy] checkpoint not found: {checkpoint_path}, training from scratch")

        return cls(agent, max_ray_dist, device)

    # ----- reward shaping utility -----

    def _compute_reward(
        self,
        prev_obs: dict | None,
        curr_obs: dict,
    ) -> tuple[float, bool]:
        """
        VERY SIMPLE placeholder reward + done.

        You MUST customize this for your environment.
        Right now:
          - reward = 0.0 every step
          - done = False

        Suggested improvements:
          - use distance moved toward some goal from pose.x/z
          - negative reward on falling/death from episode_end or health
        """
        reward = 0.0
        done = False

        # Example (you will need to match actual obs schema):
        # try:
        #     prev_pose = prev_obs["payload"]["observation"]["pose"]
        #     curr_pose = curr_obs["payload"]["observation"]["pose"]
        #     dz = curr_pose["z"] - prev_pose["z"]
        #     reward = dz   # reward forward progress
        # except Exception:
        #     reward = 0.0

        return reward, done

    def act(self, obsMsg: dict) -> dict:
        """
        Main hook called by PolicyWorker.

        1. Encode obs → vector
        2. Compute reward from prev_obs→curr_obs
        3. Store transition & train DQN
        4. Select new action
        5. Return Minecraft controls
        """
        # 1) encode observation
        obs_vec = EncodeObservation(obsMsg, self.max_ray_dist)

        # 2) if we have a previous state/action, do a training step
        if self._last_obs_vec is not None and self._last_action_idx is not None:
            reward, done = self._compute_reward(
                prev_obs=None,   # you can store full dict if you like
                curr_obs=obsMsg,
            )

            # store transition
            self.agent.StoreTransition(
                state=self._last_obs_vec,
                action=self._last_action_idx,
                reward=reward,
                nextState=obs_vec,
                done=done,
            )

            # one training step
            loss = self.agent.TrainStep()
            if loss is not None:
                # periodically update target net
                self.agent.UpdateTargetNetwork()

        # 3) select current action
        action_idx = self.agent.SelectAction(obs_vec)
        self.agent.IncrementStep()

        # 4) remember this for next step
        self._last_obs_vec = obs_vec
        self._last_action_idx = action_idx

        # 5) wrap into MC controls (with seq)
        self.seq += 1
        return ToMinecraftControls(action_idx, self.seq)
