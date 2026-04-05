import torch
from pathlib import Path
import sys

# -------------------------------
# Add project root to PYTHONPATH
# -------------------------------
PROJECT_ROOT = Path(__file__).resolve().parents[3]
sys.path.append(str(PROJECT_ROOT))

# -------------------------------
# Imports
# -------------------------------
from ai.policy.obs_encoding import OBS_DIM
from ai.policy.action_space import ActionSpace
from ai.rl.dqn.model import QNetwork
from ai.utils.runtime_paths import data_dir

# Create action space so we know output size
action_space = ActionSpace()
num_actions = action_space.num_actions()

# Create fresh untrained Q-network
model = QNetwork(OBS_DIM, num_actions)

# -------------------------------
# Save to Data/checkpoints (easy to find, matches OnlineDQN)
# -------------------------------
checkpoint_dir = data_dir() / "checkpoints"
checkpoint_dir.mkdir(parents=True, exist_ok=True)

save_path = checkpoint_dir / "dqn_initial.pt"
torch.save(model.state_dict(), save_path)

print(f"Saved INITIAL checkpoint at: {save_path}")
