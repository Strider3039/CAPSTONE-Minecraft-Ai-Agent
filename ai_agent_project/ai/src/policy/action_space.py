import json
import pathlib
import time
from typing import Dict

# ---------------------------------------------------------
# Load schema
# ---------------------------------------------------------
SchemaPath = pathlib.Path(__file__).parents[3] / "shared" / "schemas" / "action.schema.json"
with open(SchemaPath, "r") as f:
    ActionSchema = json.load(f)

# ---------------------------------------------------------
# Actions list
# ---------------------------------------------------------
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
    return NameToIndex[name]


def ActionName(idx: int) -> str:
    return ACTIONS[idx]


# ---------------------------------------------------------
# Helpers
# ---------------------------------------------------------

def BuildAction(actionId: str, payload: Dict, seq: int) -> Dict:
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


# ---------------------------------------------------------
# Main mapping function
# ---------------------------------------------------------

def ToMinecraftControls(idx: int, seq: int) -> Dict:
    """
    Convert a discrete DQN action -> Minecraft bridge action message.
    Complies with action.schema.json
    """
    name = ActionName(idx)

    # NOOP: valid action that does nothing (zero-look)
    if name == "noop":
        return BuildAction(
            "look",
            {
                "look": {
                    "dYaw": 0.0,
                    "dPitch": 0.0,
                    "turn_speed": 1.0,
                }
            },
            seq,
        )

    # Movement ----------------------------------------------------
    if name == "move_forward":
        return BuildAction(
            "move",
            {
                "move": {
                    "forward": MoveSpeed,
                    "strafe": 0.0,
                    "speed": MoveSpeed,
                }
            },
            seq,
        )

    if name == "move_back":
        return BuildAction(
            "move",
            {
                "move": {
                    "forward": -MoveSpeed,
                    "strafe": 0.0,
                    "speed": MoveSpeed,
                }
            },
            seq,
        )

    if name == "strafe_left":
        return BuildAction(
            "move",
            {
                "move": {
                    "forward": 0.0,
                    "strafe": -MoveSpeed,
                    "speed": MoveSpeed,
                }
            },
            seq,
        )

    if name == "strafe_right":
        return BuildAction(
            "move",
            {
                "move": {
                    "forward": 0.0,
                    "strafe": MoveSpeed,
                    "speed": MoveSpeed,
                }
            },
            seq,
        )

    # Jump -------------------------------------------------------
    # Schema expects boolean for payload.jump
    if name == "jump":
        return BuildAction("jump", {"jump": True}, seq)

    # Look --------------------------------------------------------
    if name == "look_left_small":
        return BuildAction(
            "look",
            {
                "look": {
                    "dYaw": -LookStep,
                    "dPitch": 0.0,
                    "turn_speed": 1.0,
                }
            },
            seq,
        )

    if name == "look_right_small":
        return BuildAction(
            "look",
            {
                "look": {
                    "dYaw": LookStep,
                    "dPitch": 0.0,
                    "turn_speed": 1.0,
                }
            },
            seq,
        )

    if name == "look_up_small":
        return BuildAction(
            "look",
            {
                "look": {
                    "dYaw": 0.0,
                    "dPitch": -LookStep,
                    "turn_speed": 1.0,
                }
            },
            seq,
        )

    if name == "look_down_small":
        return BuildAction(
            "look",
            {
                "look": {
                    "dYaw": 0.0,
                    "dPitch": LookStep,
                    "turn_speed": 1.0,
                }
            },
            seq,
        )

    # Actions ------------------------------------------------------
    if name == "attack":
        return BuildAction("attack", {"attack": True}, seq)

    if name == "use":
        return BuildAction("use", {"use": True}, seq)

    if name == "sneak":
        return BuildAction("sneak", {"sneak": True}, seq)

    # Hotbar selection --------------------------------------------
    if name.startswith("select_slot_"):
        slot = int(name.split("_")[-1])
        return BuildAction("select_slot", {"select_slot": slot}, seq)

    raise ValueError(f"Unknown action index: {idx}")


# ---------------------------------------------------------
# ActionSpace Wrapper (used by DQN)
# ---------------------------------------------------------

class ActionSpace:
    def __init__(self):
        self.actions = ACTIONS
        self.num = len(ACTIONS)

    def num_actions(self) -> int:
        return self.num

    def name(self, idx: int) -> str:
        return ACTIONS[idx]

    def to_minecraft_controls(self, idx: int, seq: int = 0) -> dict:
        return ToMinecraftControls(idx, seq)
