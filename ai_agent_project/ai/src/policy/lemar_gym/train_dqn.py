import os
import csv
import numpy as np
import torch

from lemar_gym.mock_env import MockMinecraftEnv
from lemar_gym.dqn_policy import DQNPolicy, ReplayBuffer

CHECKPOINT_PATH = "runs/mock_dqn/final.pt"
METRICS_PATH = "runs/mock_dqn/metrics.csv"


def train(total_steps=150_000, fresh_start=False):
    env = MockMinecraftEnv()
    obs_dim = env.observation_space.shape[0]
    act_dim = env.action_space.n

    policy = DQNPolicy(obs_dim, act_dim)
    buffer = ReplayBuffer(50_000)

    # =========== LOAD PREVIOUS MODEL ===========
    if not fresh_start and os.path.exists(CHECKPOINT_PATH):
        print("✓ Loading previous model:", CHECKPOINT_PATH)
        policy.q_net.load_state_dict(torch.load(CHECKPOINT_PATH))
        policy.target_net.load_state_dict(policy.q_net.state_dict())
    else:
        print("⚠ Starting fresh training (no loaded model).")

    eps_start, eps_end, eps_decay = 1.0, 0.05, 100_000
    eps = eps_start
    step = 0
    ep_return, ep_len = 0.0, 0
    metrics = []

    os.makedirs("runs/mock_dqn", exist_ok=True)
    obs, _ = env.reset()

    while step < total_steps:
        action = policy.select_action(obs, eps)
        next_obs, reward, done, trunc, info = env.step(action)

        buffer.push(obs, action, reward, next_obs, done)
        policy.update(buffer)

        obs = next_obs
        ep_return += reward
        ep_len += 1

        eps = max(eps_end, eps_start - step / eps_decay)
        step += 1

        if done or trunc:
            success_flag = bool(info.get("success", False))
            metrics.append((step, ep_return, ep_len, success_flag))

            # rolling log every 50 episodes
            if len(metrics) % 50 == 0:
                last = metrics[-50:]
                avg_return = np.mean([m[1] for m in last])
                avg_len = np.mean([m[2] for m in last])
                success_rate = np.mean([m[3] for m in last])

                print(
                    f"step {step:7d} | "
                    f"avg_return(50)={avg_return:7.2f} | "
                    f"avg_len={avg_len:6.1f} | "
                    f"success={success_rate:4.2f} | "
                    f"eps={eps:5.3f}"
                )

                # checkpoint
                torch.save(policy.q_net.state_dict(), CHECKPOINT_PATH)

            obs, _ = env.reset()
            ep_return, ep_len = 0.0, 0

    # FINAL SAVE
    torch.save(policy.q_net.state_dict(), CHECKPOINT_PATH)

    with open(METRICS_PATH, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["step", "ep_return", "ep_len", "success"])
        writer.writerows(metrics)

    print("✔ Training complete. Model saved.")


if __name__ == "__main__":
    # set fresh_start=True if you want to ignore the old model
    train(fresh_start=False)
