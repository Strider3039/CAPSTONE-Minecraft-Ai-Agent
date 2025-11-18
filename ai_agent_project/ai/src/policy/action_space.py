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
    "attack",
    "use",
    "sneak",
    "select_slot_0",
    "select_slot_1",
    "select_slot_2",
    "select_slot_3",
    "select_slot_4",
    "select_slot_5",
    "select_slot_6",
    "select_slot_7",
    "select_slot_8",
]

NUM_ACTIONS = len(ACTIONS)
NameToIndex = {name: i for i, name in enumerate(ACTIONS)}

def ActionIndex(name: str) -> int:
    """
    Return index for an action name.
    """
    return NameToIndex[name]

def ActionName(idx: int) -> str:
    """
    Return action name for an index.
    """
    return ACTIONS[idx]

def BuildAction(actionId: str, payload: Dict, seq: int) -> Dict:
    """
    Build a Minecraft action message.
    """
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
    """
    Map discrete index -> Minecraft action payload.
    """

    name = ActionName(idx)

    if name == "noop":
        return BuildAction("noop", {}, seq)
    if name == "move_forward":
        return BuildAction("move_forward",
            {"move": {"forward": MoveSpeed, "strafe": 0.0, "speed": MoveSpeed}}, seq)

    if name == "move_back":
        return BuildAction("move_back",
            {"move": {"forward": -MoveSpeed, "strafe": 0.0, "speed": MoveSpeed}}, seq)

    if name == "strafe_left":
        return BuildAction("strafe_left",
            {"move": {"forward": 0.0, "strafe": -MoveSpeed, "speed": MoveSpeed}}, seq)

    if name == "strafe_right":
        return BuildAction("strafe_right",
            {"move": {"forward": 0.0, "strafe": MoveSpeed, "speed": MoveSpeed}}, seq)

    if name == "jump":
        return BuildAction("jump", {"jump": True}, seq)
    
    if name == "look_left_small":
        return BuildAction("look_left_small",
            {"look": {"dYaw": -LookStep, "dPitch": 0.0, "turn_speed": 1.0}}, seq)

    if name == "look_right_small":
        return BuildAction("look_right_small",
            {"look": {"dYaw": LookStep, "dPitch": 0.0, "turn_speed": 1.0}}, seq)

    if name == "look_up_small":
        return BuildAction("look_up_small",
            {"look": {"dYaw": 0.0, "dPitch": -LookStep, "turn_speed": 1.0}}, seq)

    if name == "look_down_small":
        return BuildAction("look_down_small",
            {"look": {"dYaw": 0.0, "dPitch": LookStep, "turn_speed": 1.0}}, seq)
    
    if name == "attack":
        return BuildAction("attack", {"attack": True}, seq)

    if name == "use":
        return BuildAction("use", {"use": True}, seq)

    if name == "sneak":
        return BuildAction("sneak", {"sneak": True}, seq)
    
    if name.startswith("select_slot_"):
        slot = int(name.split("_")[-1])
        return BuildAction("select_slot", {"select_slot": slot}, seq)

    raise ValueError(f"Unknown action index: {idx}")