import torch
from pathlib import Path
import sys

# -------------------------------
# Add ai/src to PYTHONPATH
# -------------------------------
SRC_ROOT = Path(__file__).resolve().parents[3]  # ai/src
sys.path.append(str(SRC_ROOT))

# -------------------------------
# Imports (use ai.src.* like the rest of the codebase)
# -------------------------------
from ai.src.policy.obs_encoding import OBS_DIM
from ai.src.policy.action_space import ActionSpace
from ai.src.policy.rl.dqn.model import QNetwork

# Create action space so we know output size
action_space = ActionSpace()
num_actions = action_space.num_actions()

# Create fresh untrained Q-network
model = QNetwork(OBS_DIM, num_actions)

# -------------------------------
# Save to shared/Data/checkpoints (easy to find, matches OnlineDQN)
# -------------------------------
here = Path(__file__).resolve()
project_root = here.parents[5]  # ai_agent_project
shared_dir = project_root / "shared"
data_dir = shared_dir / "Data"
checkpoint_dir = data_dir / "checkpoints"
checkpoint_dir.mkdir(parents=True, exist_ok=True)

save_path = checkpoint_dir / "dqn_initial.pt"
torch.save(model.state_dict(), save_path)

print(f"Saved INITIAL checkpoint at: {save_path}")
