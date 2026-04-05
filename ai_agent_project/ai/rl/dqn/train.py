# ai/policy/rl/dqn/train.py

from typing import Any, Dict, Optional
import os
import csv
import time
import random
import numpy as np
import torch

from ai.rl.dqn.agent import DQNAgent

def _set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)

def train_agent(env: Any, config: Dict[str, Any]) -> None:
    """
    Main DQN training loop operating on a Gym-like environment.

    Env API:
        obs = env.reset()                       # -> np.ndarray (obs vector)
        next_obs, reward, done, info = env.step(action_idx)

    Config keys (all optional, with defaults):

        seed: int = 0

        # Agent / DQN hyperparameters
        gamma: float = 0.99
        lr: float = 1e-3
        buffer_capacity: int = 100_000
        batch_size: int = 64
        min_replay_size: int = 1_000
        target_update_interval: int = 1_000
        epsilon_start: float = 1.0
        epsilon_end: float = 0.05
        epsilon_decay_steps: int = 100_000
        hidden_sizes: list[int] = [128, 128]
        device: str = "cpu"  # or "cuda"

        # Training loop control
        num_episodes: int = 500
        max_steps_per_episode: int = 1_000

        # Logging / checkpoints
        log_every_episodes: int = 10
        log_csv_path: Optional[str] = None
        save_every_episodes: int = 50
        checkpoint_dir: Optional[str] = None
        checkpoint_prefix: str = "dqn"
    """

    # -----------------------------
    # Unpack config with defaults
    # -----------------------------
    seed = int(config.get("seed", 0))
    gamma = float(config.get("gamma", 0.99))
    lr = float(config.get("lr", 1e-3))
    buffer_capacity = int(config.get("buffer_capacity", 100_000))
    batch_size = int(config.get("batch_size", 64))
    min_replay_size = int(config.get("min_replay_size", 1_000))
    target_update_interval = int(config.get("target_update_interval", 1_000))
    epsilon_start = float(config.get("epsilon_start", 1.0))
    epsilon_end = float(config.get("epsilon_end", 0.05))
    epsilon_decay_steps = int(config.get("epsilon_decay_steps", 100_000))
    hidden_sizes = config.get("hidden_sizes", [128, 128])
    device = str(config.get("device", "cpu"))

    num_episodes = int(config.get("num_episodes", 500))
    max_steps_per_episode = int(config.get("max_steps_per_episode", 1_000))

    log_every_episodes = int(config.get("log_every_episodes", 10))
    log_csv_path = config.get("log_csv_path")
    save_every_episodes = int(config.get("save_every_episodes", 50))
    checkpoint_dir = config.get("checkpoint_dir")
    checkpoint_prefix = config.get("checkpoint_prefix", "dqn")

    # -----------------------------
    # Setup
    # -----------------------------
    _set_seed(seed)

    agent = DQNAgent(
        gamma=gamma,
        lr=lr,
        bufferCapacity=buffer_capacity,
        batchSize=batch_size,
        device=device,
        minReplaySize=min_replay_size,
        targetUpdateFreq=target_update_interval,
        epsilonStart=epsilon_start,
        epsilonEnd=epsilon_end,
        epsilonDecay=epsilon_decay_steps,
        hiddenDims=hidden_sizes,
    )


    if checkpoint_dir is not None:
        os.makedirs(checkpoint_dir, exist_ok=True)

    csv_writer = None
    csv_file = None
    if log_csv_path is not None:
        os.makedirs(os.path.dirname(log_csv_path), exist_ok=True)
        csv_file = open(log_csv_path, "w", newline="", encoding="utf-8")
        csv_writer = csv.writer(csv_file)
        csv_writer.writerow(
            [
                "episode",
                "env_steps_total",
                "episode_return",
                "episode_length",
                "epsilon",
                "last_loss",
                "wall_time_s",
            ]
        )
        csv_file.flush()

    start_time = time.time()

    # -----------------------------
    # Training loop
    # -----------------------------
    for episode in range(1, num_episodes + 1):
        obs = env.reset()  # np.ndarray (obs vector)
        done = False

        ep_return = 0.0
        ep_length = 0
        last_loss: Optional[float] = None

        while not done and ep_length < max_steps_per_episode:
            # Epsilon-greedy action from current obs
            action_idx = agent.SelectAction(obs)

            # Env step
            next_obs, reward, done, info = env.step(action_idx)

            # Store transition
            agent.StoreTransition(
                state=obs,
                action=action_idx,
                reward=reward,
                nextState=next_obs,
                done=done,
            )

            # One training step (if replay is warm enough)
            loss = agent.TrainStep()
            if loss is not None:
                last_loss = loss

            # Bookkeeping
            ep_return += float(reward)
            ep_length += 1
            obs = next_obs

            # Step & maybe update target net
            agent.IncrementStep()
            agent.UpdateTargetNetwork()

        # ------------- episode end -------------

        env_steps_total = agent.totalSteps
        epsilon = agent.CurrentEpsilon()
        wall_time_s = time.time() - start_time

        # Logging to console
        if episode % log_every_episodes == 0 or episode == 1:
            print(
                f"[Episode {episode}/{num_episodes}] "
                f"return={ep_return:.2f} len={ep_length} "
                f"eps={epsilon:.3f} steps={env_steps_total} "
                f"loss={last_loss if last_loss is not None else 'n/a'}"
            )

        # Logging to CSV
        if csv_writer is not None:
            csv_writer.writerow(
                [
                    episode,
                    env_steps_total,
                    ep_return,
                    ep_length,
                    epsilon,
                    last_loss if last_loss is not None else "",
                    f"{wall_time_s:.3f}",
                ]
            )
            csv_file.flush()

        # Checkpointing
        if checkpoint_dir is not None and (
            episode % save_every_episodes == 0 or episode == num_episodes
        ):
            ckpt_path = os.path.join(
                checkpoint_dir,
                f"{checkpoint_prefix}_ep{episode:05d}.pt",
            )
            print(f"Saving checkpoint to: {ckpt_path}")
            agent.Save(ckpt_path)

    # Cleanup
    if csv_file is not None:
        csv_file.close()
