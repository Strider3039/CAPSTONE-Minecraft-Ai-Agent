import random, torch, torch.nn as nn, torch.optim as optim
from collections import deque
import numpy as np

class DQN(nn.Module):
    def __init__(self, input_dim, output_dim):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(input_dim, 128), nn.ReLU(),
            nn.Linear(128, 128), nn.ReLU(),
            nn.Linear(128, output_dim)
        )

    def forward(self, x):
        return self.net(x)

class ReplayBuffer:
    def __init__(self, capacity=50000):
        self.buffer = deque(maxlen=capacity)

    def push(self, state, action, reward, next_state, done):
        self.buffer.append((state, action, reward, next_state, done))

    def sample(self, batch_size):
        batch = random.sample(self.buffer, batch_size)
        s, a, r, ns, d = zip(*batch)
        return np.array(s), a, r, np.array(ns), d

    def __len__(self): return len(self.buffer)

class DQNPolicy:
    def __init__(self, obs_dim, act_dim, lr=5e-4, gamma=0.99):
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.q_net = DQN(obs_dim, act_dim).to(self.device)
        self.target_net = DQN(obs_dim, act_dim).to(self.device)
        self.target_net.load_state_dict(self.q_net.state_dict())
        self.optimizer = optim.Adam(self.q_net.parameters(), lr=lr)
        self.gamma = gamma
        self.steps = 0

    def select_action(self, obs, eps):
        if random.random() < eps:
            return random.randrange(self.q_net.net[-1].out_features)
        with torch.no_grad():
            obs_t = torch.tensor(obs, dtype=torch.float32, device=self.device).unsqueeze(0)
            q_vals = self.q_net(obs_t)
            return int(torch.argmax(q_vals, dim=1).item())

    def update(self, buffer, batch_size=64):
        if len(buffer) < batch_size: return
        s, a, r, ns, d = buffer.sample(batch_size)
        s_t = torch.tensor(s, dtype=torch.float32, device=self.device)
        ns_t = torch.tensor(ns, dtype=torch.float32, device=self.device)
        a_t = torch.tensor(a, dtype=torch.long, device=self.device).unsqueeze(1)
        r_t = torch.tensor(r, dtype=torch.float32, device=self.device).unsqueeze(1)
        d_t = torch.tensor(d, dtype=torch.float32, device=self.device).unsqueeze(1)

        q_vals = self.q_net(s_t).gather(1, a_t)
        with torch.no_grad():
            max_next_q = self.target_net(ns_t).max(1, keepdim=True)[0]
            target_q = r_t + self.gamma * max_next_q * (1 - d_t)

        loss = nn.MSELoss()(q_vals, target_q)
        self.optimizer.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(self.q_net.parameters(), 1.0)
        self.optimizer.step()

        if self.steps % 1000 == 0:
            self.target_net.load_state_dict(self.q_net.state_dict())
        self.steps += 1
