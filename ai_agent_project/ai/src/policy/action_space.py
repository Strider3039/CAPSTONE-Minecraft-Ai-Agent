import json
import pathlib
import time
from typing import Dict

# Load schema
SchemaPath = pathlib.Path(__file__).parents[3] / "shared" / "schemas" / "action.schema.json"
with open(SchemaPath, "r") as f:
    ActionSchema = json.load(f)

# Actions list
ACTIONS = [
    "noop",
    "move_forward",
    "move_back",
    "strafe_left",
    "strafe_right",
    "jump",
    "look_left_small",
    "look_right_small",
    "look_up_small",
    "look_down_small",
]

NUM_ACTIONS = len(ACTIONS)
NameToIndex = {name: i for i, name in enumerate(ACTIONS)}

def ActionIndex(name: str) -> int:
    """Return index for an action name."""
    return NameToIndex[name]

def ActionName(idx: int) -> str:
    """Return action name for an index."""
    return ACTIONS[idx]

def BuildAction(actionId: str, payload: Dict, seq: int) -> Dict:
    """Build a Minecraft action message."""
    return {
        "proto": "1",
        "kind": "action",
        "seq": seq,
        "timestamp": time.time(),
        "action_id": actionId,
        "payload": payload,
    }

LookStep = 10.0
MoveSpeed = 1.0

def ToMinecraftControls(idx: int, seq: int) -> Dict:
    """Map discrete index -> Minecraft action payload."""
    name = ActionName(idx)

    if name == "noop":
        return BuildAction("noop", {}, seq)

    if name == "move_forward":
        payload = {"move": {"forward": MoveSpeed, "strafe": 0.0, "speed": MoveSpeed}}
        return BuildAction("move_forward", payload, seq)

    if name == "move_back":
        payload = {"move": {"forward": -MoveSpeed, "strafe": 0.0, "speed": MoveSpeed}}
        return BuildAction("move_back", payload, seq)

    if name == "strafe_left":
        payload = {"move": {"forward": 0.0, "strafe": -MoveSpeed, "speed": MoveSpeed}}
        return BuildAction("strafe_left", payload, seq)

    if name == "strafe_right":
        payload = {"move": {"forward": 0.0, "strafe": MoveSpeed, "speed": MoveSpeed}}
        return BuildAction("strafe_right", payload, seq)

    if name == "jump":
        return BuildAction("jump", {"jump": True}, seq)

    if name == "look_left_small":
        payload = {"look": {"dYaw": -LookStep, "dPitch": 0.0, "turn_speed": 1.0}}
        return BuildAction("look_left_small", payload, seq)

    if name == "look_right_small":
        payload = {"look": {"dYaw": LookStep, "dPitch": 0.0, "turn_speed": 1.0}}
        return BuildAction("look_right_small", payload, seq)

    if name == "look_up_small":
        payload = {"look": {"dYaw": 0.0, "dPitch": -LookStep, "turn_speed": 1.0}}
        return BuildAction("look_up_small", payload, seq)

    if name == "look_down_small":
        payload = {"look": {"dYaw": 0.0, "dPitch": LookStep, "turn_speed": 1.0}}
        return BuildAction("look_down_small", payload, seq)

    raise ValueError(f"Unknown action index {idx}")
