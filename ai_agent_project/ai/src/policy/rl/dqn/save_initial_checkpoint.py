import torch
from pathlib import Path
import sys

# -------------------------------
# Add ai/src to PYTHONPATH
# -------------------------------
ROOT = Path(__file__).resolve().parents[3]  # ai_agent_project/ai/src
sys.path.append(str(ROOT))

# -------------------------------
# Correct imports
# -------------------------------
from policy.obs_encoding import OBS_DIM
from policy.action_space import ActionSpace
from model import QNetwork

# Create action space so we know output size
action_space = ActionSpace()
num_actions = action_space.num_actions()

# Create fresh untrained Q-network
model = QNetwork(OBS_DIM, num_actions)

# Path to save inside policy/runs/checkpoints/
checkpoint_dir = Path(__file__).resolve().parents[2] / "runs" / "checkpoints"
checkpoint_dir.mkdir(parents=True, exist_ok=True)

save_path = checkpoint_dir / "dqn_initial.pt"
torch.save(model.state_dict(), save_path)

print(f"Saved INITIAL checkpoint at: {save_path}")
