# train_DQN.py
# Robust, readable DQN for MockMinecraftEnv (Gymnasium-like API).
# Python 3.8+, PyTorch CPU ok. No external RL libs required.

import os
import csv
import math
import time
import random
from dataclasses import dataclass
from collections import deque, namedtuple
from typing import Tuple, Optional

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

# Import your env
from mock_env import MockMinecraftEnv, DEFAULT_CFG

# =========================
# Config / Hyperparameters
# =========================
SEED                = 7
TOTAL_STEPS         = 50_000        # total env steps (training + exploration)
REPLAY_CAPACITY     = 100_000
BATCH_SIZE          = 64
GAMMA               = 0.99
LR                  = 5e-4
TARGET_UPDATE_EVERY = 1_000         # gradient steps
WARMUP_STEPS        = 2_000         # minimum buffer fill before training
GRAD_CLIP_NORM      = 5.0

# ε-greedy schedule: linear decay
EPS_START           = 1.0
EPS_END             = 0.10
EPS_DECAY_STEPS     = 40_000

# Logging / Checkpoints
RUN_DIR             = "./runs"
ENV_LOG_DIR         = os.path.join(RUN_DIR, "env_logs")
LOG_CSV             = os.path.join(RUN_DIR, "train_log.csv")
CKPT_LAST           = os.path.join(RUN_DIR, "dqn_last.pt")
CKPT_BEST           = os.path.join(RUN_DIR, "dqn_best.pt")

EVAL_EVERY_STEPS    = 2_000         # eval cadence
EVAL_EPISODES       = 5

# =========================
# Utilities
# =========================
def set_seed(seed: int):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)

def ensure_dir(path: str):
    os.makedirs(path, exist_ok=True)

def lin_eps(step: int) -> float:
    if step >= EPS_DECAY_STEPS:
        return EPS_END
    frac = step / EPS_DECAY_STEPS
    return EPS_START + frac * (EPS_END - EPS_START)

Transition = namedtuple("Transition", "s a r s2 d")

# =========================
# Replay Buffer
# =========================
class Replay:
    def __init__(self, capacity: int):
        self.buf = deque(maxlen=capacity)

    def push(self, s, a, r, s2, d):
        self.buf.append(Transition(s, a, r, s2, d))

    def sample(self, batch_size: int) -> Transition:
        batch = random.sample(self.buf, batch_size)
        return Transition(*zip(*batch))

    def __len__(self):
        return len(self.buf)

# =========================
# Q-network (small MLP)
# =========================
class QNet(nn.Module):
    def __init__(self, obs_dim: int, act_dim: int):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(obs_dim, 128),
            nn.ReLU(),
            nn.Linear(128, 128),
            nn.ReLU(),
            nn.Linear(128, act_dim),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x)

# =========================
# Acting
# =========================
def act_eps_greedy(qnet: QNet, obs: np.ndarray, eps: float, act_dim: int) -> int:
    if random.random() < eps:
        return random.randrange(act_dim)
    with torch.no_grad():
        q = qnet(torch.tensor(obs, dtype=torch.float32).unsqueeze(0))
        return int(q.argmax(dim=1).item())

# =========================
# Environment helpers
# =========================
@dataclass
class EpisodeState:
    obs: np.ndarray
    done: bool
    steps: int
    ret: float

def reset_env(env: MockMinecraftEnv, seed_offset: int) -> EpisodeState:
    obs, info = env.reset(seed=seed_offset)
    return EpisodeState(obs=obs, done=False, steps=0, ret=0.0)

def safe_step(env: MockMinecraftEnv, action: int) -> Tuple[np.ndarray, float, bool, dict]:
    """
    Calls env.step(action) and returns (obs2, reward, done, info).
    Treats 'terminated or truncated' as done=True. Does NOT swallow exceptions
    other than turning truncated/terminated into done flags.
    """
    obs2, r, terminated, truncated, info = env.step(action)
    done = bool(terminated or truncated)
    return obs2, float(r), done, info

# =========================
# Evaluation (no learning)
# =========================
def evaluate(qnet, episodes: int = 5):
    eval_env = MockMinecraftEnv(DEFAULT_CFG)
    eval_env.enable_csv_logging = False  # keep eval light
    rets, lens = [], []
    for i in range(episodes):
        obs, info = eval_env.reset(seed=SEED + 1000 + i)
        done, ep_ret, ep_len = False, 0.0, 0
        while not done:
            with torch.no_grad():
                q = qnet(torch.tensor(obs, dtype=torch.float32).unsqueeze(0))
                a = int(q.argmax(dim=1).item())
            obs, r, term, trunc, _ = eval_env.step(a)
            done = bool(term or trunc)
            ep_ret += r
            ep_len += 1
        rets.append(ep_ret)
        lens.append(ep_len)
    return float(np.mean(rets)), float(np.std(rets)), int(np.mean(lens))


# =========================
# Training
# =========================
def train():
    set_seed(SEED)
    ensure_dir(RUN_DIR)
    ensure_dir(ENV_LOG_DIR)

    # Env (disable per-step CSV logging for speed)
    env = MockMinecraftEnv(DEFAULT_CFG)
    env.enable_csv_logging = False
    env.log_dir = ENV_LOG_DIR

    obs_dim, act_dim = 10, 7
    q = QNet(obs_dim, act_dim)
    tgt = QNet(obs_dim, act_dim)
    tgt.load_state_dict(q.state_dict())
    opt = torch.optim.Adam(q.parameters(), lr=LR)
    replay = Replay(REPLAY_CAPACITY)

    # Prepare log CSV
    if not os.path.exists(LOG_CSV):
        with open(LOG_CSV, "w", newline="", encoding="utf-8") as f:
            csv.writer(f).writerow(["step", "episode", "episode_return", "epsilon", "eval_mean", "eval_std", "eval_steps"])

    global_step = 0
    grad_steps = 0
    episode_idx = 0
    best_eval_mean = -float("inf")

    t0 = time.time()
    try:
        while global_step < TOTAL_STEPS:
            # ---- Begin new episode
            ep = reset_env(env, seed_offset=SEED + episode_idx)

            while (not ep.done) and (global_step < TOTAL_STEPS):
                # ε-greedy action
                eps = lin_eps(global_step)
                a = act_eps_greedy(q, ep.obs, eps, act_dim)

                # env step
                obs2, r, done, _ = safe_step(env, a)

                # store transition
                replay.push(ep.obs, a, r, obs2, float(done))

                # bookkeeping
                ep.ret += r
                ep.steps += 1
                global_step += 1
                ep.obs = obs2
                ep.done = done

                # Learn if warm-up done
                if len(replay) >= max(BATCH_SIZE, WARMUP_STEPS):
                    batch = replay.sample(BATCH_SIZE)
                    s   = torch.tensor(np.array(batch.s),  dtype=torch.float32)
                    a_t = torch.tensor(np.array(batch.a),  dtype=torch.int64).unsqueeze(1)
                    r_t = torch.tensor(np.array(batch.r),  dtype=torch.float32).unsqueeze(1)
                    s2  = torch.tensor(np.array(batch.s2), dtype=torch.float32)
                    d_t = torch.tensor(np.array(batch.d),  dtype=torch.float32).unsqueeze(1)

                    # Q(s,a)
                    q_sa = q(s).gather(1, a_t)

                    # Target: r + gamma * (1 - done) * max_a' Q_tgt(s',a')
                    with torch.no_grad():
                        q_next = tgt(s2).max(dim=1, keepdim=True)[0]
                        target = r_t + GAMMA * (1.0 - d_t) * q_next

                    loss = F.smooth_l1_loss(q_sa, target)

                    opt.zero_grad()
                    loss.backward()
                    nn.utils.clip_grad_norm_(q.parameters(), GRAD_CLIP_NORM)
                    opt.step()
                    grad_steps += 1

                    # Periodic target sync
                    if grad_steps % TARGET_UPDATE_EVERY == 0:
                        tgt.load_state_dict(q.state_dict())

                # Periodic eval + log
                if global_step % EVAL_EVERY_STEPS == 0:
                    eval_mean, eval_std, eval_steps = evaluate(q)
                    with open(LOG_CSV, "a", newline="", encoding="utf-8") as f:
                        csv.writer(f).writerow([global_step, episode_idx, f"{ep.ret:.3f}", f"{eps:.3f}", f"{eval_mean:.3f}", f"{eval_std:.3f}", eval_steps])

                    # Save "last" checkpoint
                    torch.save({"model": q.state_dict(), "obs_dim": obs_dim, "act_dim": act_dim,
                                "global_step": global_step, "episode": episode_idx}, CKPT_LAST)
                    # Save "best" checkpoint
                    if eval_mean > best_eval_mean:
                        best_eval_mean = eval_mean
                        torch.save({"model": q.state_dict(), "obs_dim": obs_dim, "act_dim": act_dim,
                                    "global_step": global_step, "episode": episode_idx,
                                    "best_eval_mean": best_eval_mean}, CKPT_BEST)

            # ---- Episode end
            episode_idx += 1

        # Final save
        torch.save({"model": q.state_dict(), "obs_dim": obs_dim, "act_dim": act_dim,
                    "global_step": global_step, "episode": episode_idx}, CKPT_LAST)

    except KeyboardInterrupt:
        print("\nInterrupted — saving last checkpoint.")
        torch.save({"model": q.state_dict(), "obs_dim": obs_dim, "act_dim": act_dim,
                    "global_step": global_step, "episode": episode_idx}, CKPT_LAST)

    elapsed = time.time() - t0
    print(f"Done. Steps={global_step}, Episodes={episode_idx}, Elapsed={elapsed:.1f}s")
    print(f"Last ckpt: {CKPT_LAST}")
    if os.path.exists(CKPT_BEST):
        print(f"Best ckpt: {CKPT_BEST} (mean return ~= {best_eval_mean:.2f})")
    print(f"Logs: {LOG_CSV}")

# =========================
# Entrypoint
# =========================
if __name__ == "__main__":
    train()
