from __future__ import annotations
import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim

from ai.src.policy import Policy
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
        self.replayBuffer = ReplayBuffer(bufferCapacity, OBS_DIM)

    
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
    Inference-time policy for use in the live bridge.

    - Takes raw Observation messages.
    - Encodes them into vectors.
    - Runs the Q-network.
    - Returns a Minecraft Action payload dict.

    (No Training)
    """

    def __init__(
        self, 
        model: QNetwork,
        maxRayDistance: float,
        device: str = "cpu",
    ) -> None:
        self.model = model.to(device)
        self.model.eval()
        self.maxRayDistance = maxRayDistance
        self.device = device

    @classmethod
    def FromCheckpoint(
        cls,
        checkpointPath: str,
        maxRayDistance: float,
        device: str = "cpu",
        hiddenDims=(128, 128),
    ) -> DQNPolicy:
        """
        Convenience constructor: build QNetwork, load weights, wrap in DQNPolicy.
        """

        model = QNetwork(OBS_DIM, NUM_ACTIONS, hiddenDims)
        checkpoint = torch.load(checkpointPath, map_location=device)
        model.load_state_dict(checkpoint["qNet"])

        return cls(model, maxRayDistance, device)
    
    def act(self, obsMsg: dict) -> dict:
        """
        Compute and return an action given a Minecraft observation.
        Must return a dictionary matching action.schema.json.
        """

        # Encode observation to vector
        obsVec = EncodeObservation(obsMsg, self.maxRayDistance)
        obsTensor = torch.from_numpy(obsVec).unsqueeze(0).to(self.device)

        # Compute Q-values
        with torch.no_grad():
            qValues = self.model(obsTensor)
            actionIdx = int(torch.argmax(qValues, dim=1).item())

        # Convert action index to Minecraft action payload
        return ToMinecraftControls(actionIdx)