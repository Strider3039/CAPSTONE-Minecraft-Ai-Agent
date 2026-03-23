# ai/src/policy/rl/dqn/agent.py

from __future__ import annotations

import json
import os
import time
from datetime import datetime, timezone, timedelta
import pathlib as _pathlib

try:
    from zoneinfo import ZoneInfo
except ImportError:
    ZoneInfo = None  # Python < 3.9

# Pacific (PST/PDT); fallback to fixed UTC-8 if zoneinfo unavailable
def _pacific_now():
    if ZoneInfo is not None:
        return datetime.now(ZoneInfo("America/Los_Angeles"))
    return datetime.now(timezone(timedelta(hours=-8)))

import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim

from ai.src.policy.base import Policy
from ai.src.policy.action_space import NUM_ACTIONS, ToMinecraftControls
from ai.src.policy.obs_encoding import OBS_DIM, EncodeObservation
from .model import QNetwork  # relative import
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
        gamma: float = 0.99,
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

        # Epsilon schedule
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

        # Replay buffer
        self.replayBuffer = ReplayBuffer(bufferCapacity)

    def CurrentEpsilon(self) -> float:
        frac = min(float(self.totalSteps) / self.epsilonDecay, 1.0)
        return self.epsilonStart + frac * (self.epsilonEnd - self.epsilonStart)

    def apply_config(self, cfg: dict) -> None:
        """Update exploration/training params from config (e.g. runtime.policy.dqn). Hot-reload safe."""
        if not isinstance(cfg, dict):
            return
        mapping = {
            "epsilon_start": ("epsilonStart", float),
            "epsilon_end": ("epsilonEnd", float),
            "epsilon_decay": ("epsilonDecay", int),
            "gamma": ("gamma", float),
            "lr": ("lr", float),
            "batch_size": ("batchSize", int),
            "min_replay_size": ("minReplaySize", int),
            "target_update_freq": ("targetUpdateFreq", int),
        }
        for cfg_key, (attr, cast_fn) in mapping.items():
            if cfg_key in cfg and cfg[cfg_key] is not None:
                try:
                    setattr(self, attr, cast_fn(cfg[cfg_key]))
                except (TypeError, ValueError):
                    pass

    def SelectAction(self, obsVec: np.ndarray) -> int:
        eps = self.CurrentEpsilon()
        return EpsilonGreedyAction(self.qNet, obsVec, eps, self.device)

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

        return float(loss.item())

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
        self.totalSteps = int(checkpoint.get("totalSteps", 0))


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
        self.seq = 0  # for Forge/bridge

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
    Online RL policy that learns in Minecraft using DQNAgent and a RewardEngine.

    Notes:
      - The server enqueues synthetic "episode_start" and forwards "episode_end" into obs_q.
      - We MUST handle those kinds here to avoid trying to EncodeObservation() on them.
      - Checkpoints are saved periodically so learning persists across restarts/worlds.
    """

    def __init__(
        self,
        agent: DQNAgent,
        max_ray_dist: float,
        device: str = "cpu",
        save_every_steps: int | None = None,
        reward_cfg: dict | None = None,
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

        # Reward engine (initialized from YAML if reward_cfg provided, else code defaults)
        self.reward_engine = RewardEngine()
        if isinstance(reward_cfg, dict):
            self.reward_engine.apply_config(reward_cfg)

        # Episode tracking (Python-side)
        self.episode_idx = 0
        self.episode_step = 0  # step index within current episode
        self._episode_start_time: float | None = None  # wall-clock for duration_seconds

        # Resolve shared paths (shared/Data/episode_state.json, etc.)
        (
            self._shared_dir,
            self._episode_state_path,
            self._episode_history_path,
            self._step_history_path,
        ) = self._resolve_paths()

        # ---- checkpointing (global persistence across all worlds) ----
        # Default ~5 min at 20 Hz (5*60*20 = 6000); config can override via save_every_steps
        self.save_every_steps = int(save_every_steps) if save_every_steps is not None else 6_000
        self.ckpt_latest_path = str((self._shared_dir / "Data" / "online_dqn_latest.pt").resolve())
        self.ckpt_dir = (self._shared_dir / "Data" / "checkpoints")
        self.ckpt_dir.mkdir(parents=True, exist_ok=True)

    @classmethod
    def FromCheckpoint(
        cls,
        checkpoint_path: str | None,
        max_ray_dist: float,
        device: str = "cpu",
        hidden_sizes=(128, 128),
        save_every_steps: int | None = None,
        reward_cfg: dict | None = None,
        dqn_cfg: dict | None = None,
    ) -> "OnlineDQNPolicy":
        agent = DQNAgent(device=device, hiddenDims=hidden_sizes)
        if isinstance(dqn_cfg, dict):
            agent.apply_config(dqn_cfg)

        if checkpoint_path:
            try:
                agent.Load(checkpoint_path, mapLocation=device)
                print(f"[OnlineDQNPolicy] Loaded checkpoint: {checkpoint_path}")
            except FileNotFoundError:
                print(f"[OnlineDQNPolicy] checkpoint not found: {checkpoint_path}, training from scratch")
            except Exception as e:
                print(f"[OnlineDQNPolicy] failed to load checkpoint ({checkpoint_path}): {e}")

        return cls(agent, max_ray_dist, device, save_every_steps=save_every_steps, reward_cfg=reward_cfg)

    def apply_runtime_config(self, runtime_cfg: dict) -> None:
        """Hot-reload: apply runtime.policy.reward and runtime.policy.dqn to live policy."""
        if not isinstance(runtime_cfg, dict):
            return
        policy_cfg = runtime_cfg.get("policy") or {}
        reward_cfg = policy_cfg.get("reward")
        if isinstance(reward_cfg, dict):
            self.reward_engine.apply_config(reward_cfg)
        dqn_cfg = policy_cfg.get("dqn")
        if isinstance(dqn_cfg, dict):
            self.agent.apply_config(dqn_cfg)

    # ----- filesystem helpers for logs -----

    def _resolve_paths(
        self,
    ) -> tuple[_pathlib.Path, _pathlib.Path, _pathlib.Path, _pathlib.Path]:
        """
        Resolve shared/Data paths consistently with server.py.
        """
        here = _pathlib.Path(__file__).resolve()
        shared_dir: _pathlib.Path | None = None

        for parent in here.parents:
            candidate = parent / "shared"
            if candidate.exists() and candidate.is_dir():
                shared_dir = candidate
                break

        if shared_dir is None:
            shared_dir = _pathlib.Path.cwd() / "shared"

        data_dir = shared_dir / "Data"
        data_dir.mkdir(parents=True, exist_ok=True)

        episode_state_path = data_dir / "online_dqn_episode_state.json"
        episode_history_path = data_dir / "episode_history.json"
        step_history_path = data_dir / "step_history.jsonl"

        return shared_dir, episode_state_path, episode_history_path, step_history_path

    @staticmethod
    def _atomic_write_text(path: _pathlib.Path, contents: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = path.with_name(f"{path.name}.{os.getpid()}.tmp")
        tmp_path.write_text(contents, encoding="utf-8")
        tmp_path.replace(path)

    def _write_episode_state(self, reward: float, done: bool, obsMsg: dict) -> None:
        body = obsMsg.get("payload", {})
        pose = body.get("pose", {})
        world = body.get("world", {})
        collision = body.get("collision", {})

        doc = {
            "episode_id": int(self.episode_idx),
            "python_episode_idx": int(self.episode_idx),
            "episode_step": int(self.episode_step),
            "total_steps": int(self.agent.totalSteps),
            "last_reward": float(reward),
            "episode_return": float(getattr(self.reward_engine, "episode_return", 0.0)),
            "done": bool(done),
            "pose": {
                "x": pose.get("x"),
                "y": pose.get("y"),
                "z": pose.get("z"),
                "yaw": pose.get("yaw"),
                "pitch": pose.get("pitch"),
            },
            "world": {
                "time_of_day": world.get("time_of_day"),
                "weather": world.get("weather"),
                "biome": world.get("biome"),
            },
            "collision": {
                "is_grounded": collision.get("is_grounded"),
                "is_colliding": collision.get("is_colliding"),
                "no_progress": collision.get("no_progress"),
            },
            "obs_timestamp": float(obsMsg.get("timestamp", 0.0)),
            "logged_at_unix": float(time.time()),
        }

        try:
            self._atomic_write_text(self._episode_state_path, json.dumps(doc, indent=2))
        except Exception as e:
            print(f"[OnlineDQN] failed to write {self._episode_state_path.name}: {e}")

    def _append_step_history(
        self,
        reward: float,
        done: bool,
        action_idx: int,
        obsMsg: dict,
    ) -> None:
        body = obsMsg.get("payload", {})
        pose = body.get("pose", {})
        world = body.get("world", {})
        collision = body.get("collision", {})

        row = {
            "episode": int(self.episode_idx),
            "step": int(self.episode_step),
            "action": int(action_idx),
            "reward": float(reward),
            "done": bool(done),
            "pose": {"x": pose.get("x"), "y": pose.get("y"), "z": pose.get("z")},
            "world": {"time_of_day": world.get("time_of_day"), "weather": world.get("weather")},
            "collision": {
                "is_grounded": collision.get("is_grounded"),
                "is_colliding": collision.get("is_colliding"),
                "no_progress": collision.get("no_progress"),
            },
        }

        try:
            with self._step_history_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(row) + "\n")
        except Exception as e:
            print(f"[OnlineDQN] failed to append step_history.jsonl: {e}")

    def _append_episode_history(
        self,
        last_obs: dict,
        done_reason: str = "done_true",
        success: bool | None = None,
    ) -> None:
        body = last_obs.get("payload", {}) if isinstance(last_obs, dict) else {}
        pose = body.get("pose", {}) if isinstance(body, dict) else {}
        world = body.get("world", {}) if isinstance(body, dict) else {}

        ep_return = float(getattr(self.reward_engine, "episode_return", 0.0))
        steps = int(self.episode_step) if self.episode_step > 0 else 0
        avg_reward = ep_return / steps if steps > 0 else 0.0
        duration_seconds = (
            float(time.time() - self._episode_start_time)
            if self._episode_start_time is not None
            else None
        )

        entry = {
            "episode_id": int(self.episode_idx),
            "episode_step": steps,
            "episode_return": ep_return,
            "done_reason": done_reason,
            "duration_seconds": duration_seconds,
            "success": success,
            "episode": int(self.episode_idx),
            "steps": steps,
            "return": ep_return,
            "avg_reward": float(avg_reward),
            "end_pose": {"x": pose.get("x"), "y": pose.get("y"), "z": pose.get("z")},
            "end_time_of_day": world.get("time_of_day") if isinstance(world, dict) else None,
            "reason": done_reason,
            "logged_at_unix": float(time.time()),
        }

        try:
            if self._episode_history_path.exists():
                try:
                    existing = json.loads(self._episode_history_path.read_text("utf-8"))
                    if not isinstance(existing, list):
                        existing = []
                except Exception:
                    existing = []
            else:
                existing = []

            existing.append(entry)
            self._atomic_write_text(self._episode_history_path, json.dumps(existing, indent=2))
        except Exception as e:
            print(f"[OnlineDQN] failed to append episode_history.json: {e}")

    def _compute_reward(self, prev_obs: dict | None, curr_obs: dict) -> tuple[float, bool]:
        return self.reward_engine.compute(prev_obs, curr_obs)

    def _flush_pending_transition(
        self,
        done_reason: str,
        reward: float = 0.0,
        terminal_obs_msg: dict | None = None,
    ) -> bool:
        """
        Close the last pending (s, a) when the environment ends without a fresh
        observation, such as a disconnect or explicit episode_end event.
        """
        if self._last_obs_vec is None or self._last_action_idx is None:
            return False

        next_obs_msg = terminal_obs_msg if isinstance(terminal_obs_msg, dict) else self._last_obs_msg
        if not isinstance(next_obs_msg, dict):
            next_obs_msg = {"payload": {}}

        if isinstance(terminal_obs_msg, dict) and terminal_obs_msg.get("kind") == "observation":
            next_obs_vec = EncodeObservation(terminal_obs_msg, self.max_ray_dist)
        else:
            # No post-terminal observation exists, so reuse the last encoded state
            # but force done=True to stop bootstrap leakage into the next episode.
            next_obs_vec = np.array(self._last_obs_vec, copy=True)

        self._write_episode_state(reward, True, next_obs_msg)
        self._append_step_history(reward, True, self._last_action_idx, next_obs_msg)
        self.episode_step += 1

        self.agent.StoreTransition(
            state=self._last_obs_vec,
            action=self._last_action_idx,
            reward=float(reward),
            nextState=next_obs_vec,
            done=True,
        )

        loss = self.agent.TrainStep()
        if loss is not None:
            self.agent.UpdateTargetNetwork()

        return True

    @staticmethod
    def _build_eval_control(action: str) -> dict:
        return {
            "proto": "1",
            "kind": "eval_control",
            "timestamp": time.time(),
            "payload": {"action": action},
        }

    def _reset_episode_state(self) -> None:
        """Reset Python-side episode bookkeeping and transition memory."""
        self.episode_step = 0
        self._episode_start_time = time.time()
        self.reward_engine.reset_episode()
        self._last_obs_vec = None
        self._last_action_idx = None
        self._last_obs_msg = None

    def _maybe_checkpoint(self) -> None:
        if self.save_every_steps <= 0:
            return
        # Save on first step (so a file exists after any learning) and every save_every_steps
        n = self.agent.totalSteps
        if n == 0:
            return
        if n != 1 and n % self.save_every_steps != 0:
            return

        try:
            self.agent.Save(self.ckpt_latest_path)
            snap = self.ckpt_dir / f"online_dqn_step{n:09d}.pt"
            self.agent.Save(str(snap))
            dt = _pacific_now()
            z = dt.strftime("%Z") or "PST"
            ts = dt.strftime("%Y-%m-%d %H:%M:%S") + f" {z}"
            print(f"[OnlineDQN] checkpoint saved at {ts} | step={n} | latest={self.ckpt_latest_path} | snapshot={snap.name}")
        except Exception as e:
            print(f"[OnlineDQN] checkpoint save failed: {e}")

    def shutdown(self, reason: str = "shutdown_disconnect") -> None:
        has_pending = self._flush_pending_transition(done_reason=reason, reward=0.0)
        has_episode_data = has_pending or self.episode_step > 0 or self._last_obs_msg is not None
        if not has_episode_data:
            return

        try:
            self._append_episode_history(last_obs=self._last_obs_msg or {"payload": {}}, done_reason=reason)
        except Exception as e:
            print(f"[OnlineDQN] failed to log episode_history on shutdown: {e}")

        self.episode_idx += 1
        self._reset_episode_state()

    def act(self, obsMsg: dict) -> dict:
        """
        Called by PolicyWorker every tick.

        Handles:
          - kind == "episode_start" / "episode_end" (events injected into obs_q)
          - kind == "observation" (normal RL step)
        """
        kind = obsMsg.get("kind")

        # ---- handle injected episode boundary events safely ----
        if kind == "episode_start":
            self._reset_episode_state()
            self.seq += 1
            return ToMinecraftControls(0, self.seq)  # noop

        if kind == "episode_end":
            self._flush_pending_transition(done_reason="client_episode_end", reward=0.0)
            try:
                self._append_episode_history(last_obs=self._last_obs_msg or obsMsg, done_reason="client_episode_end")
            except Exception as e:
                print(f"[OnlineDQN] failed to log episode_history on client end: {e}")

            self.episode_idx += 1
            self._reset_episode_state()
            self.seq += 1
            return ToMinecraftControls(0, self.seq)  # noop

        # If some other unexpected kind shows up, just noop safely.
        if kind is not None and kind != "observation":
            self.seq += 1
            return ToMinecraftControls(0, self.seq)

        # Ensure we have an episode start time (first observation or episode without explicit episode_start)
        if self._episode_start_time is None:
            self._episode_start_time = time.time()

        # 1) Encode current observation
        obs_vec = EncodeObservation(obsMsg, self.max_ray_dist)

        # 2) If we have a previous step, compute reward & train
        if self._last_obs_vec is not None and self._last_action_idx is not None:
            reward, done = self._compute_reward(prev_obs=self._last_obs_msg, curr_obs=obsMsg)

            # Logging
            self._write_episode_state(reward, done, obsMsg)
            self._append_step_history(reward, done, self._last_action_idx, obsMsg)

            # Episode step bookkeeping
            self.episode_step += 1

            # Store transition in replay
            self.agent.StoreTransition(
                state=self._last_obs_vec,
                action=self._last_action_idx,
                reward=reward,
                nextState=obs_vec,
                done=done,
            )

            # One gradient update
            loss = self.agent.TrainStep()
            if loss is not None:
                self.agent.UpdateTargetNetwork()

            # Episode end handling (RewardEngine done)
            if done:
                phase = getattr(self.reward_engine, "phase", None)
                episode_return = getattr(self.reward_engine, "episode_return", None)

                msg = f"[OnlineDQN] Episode {self.episode_idx} ended."
                if episode_return is not None:
                    msg += f" return={episode_return:.2f}"
                if phase is not None:
                    msg += f" phase={phase}"
                print(msg)

                try:
                    self._append_episode_history(last_obs=obsMsg, done_reason="reward_engine_done")
                except Exception as e:
                    print(f"[OnlineDQN] failed to log episode_history: {e}")

                self.episode_idx += 1
                self._reset_episode_state()
                return self._build_eval_control("start_episode")

        # 3) Select action for current state
        action_idx = self.agent.SelectAction(obs_vec)

        # 4) Step count + target net update cadence is based on totalSteps
        self.agent.IncrementStep()
        self._maybe_checkpoint()

        # 5) Remember (s, a) for the next tick
        self._last_obs_vec = obs_vec
        self._last_action_idx = action_idx
        self._last_obs_msg = obsMsg

        # 6) Wrap into Minecraft controls with sequential id
        self.seq += 1
        return ToMinecraftControls(action_idx, self.seq)