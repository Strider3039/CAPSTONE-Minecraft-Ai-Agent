import json
import pathlib
import time
from typing import Dict

from ai.utils.runtime_paths import schemas_dir

# ---------------------------------------------------------
# Load schema
# ---------------------------------------------------------
SchemaPath = schemas_dir() / "action.schema.json"
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
            "noop",
            {
                "look": {"yaw_delta": 0.0, "pitch_delta": 0.0, "turn_speed": 1.0},
                "move": {"forward": 0.0, "strafe": 0.0, "jump": False, "sprint": False, "sneak": False},
            },
            seq,
        )

    # Movement ----------------------------------------------------
    def _move(forward: float, strafe: float, jump=False, sprint=False, sneak=False):
        return BuildAction(
            "move",
            {"move": {"forward": forward, "strafe": strafe, "jump": bool(jump), "sprint": bool(sprint), "sneak": bool(sneak)}},
            seq,
        )

    if name == "move_forward":
        return _move(MoveSpeed, 0.0)

    if name == "move_back":
        return _move(-MoveSpeed, 0.0)

    if name == "strafe_left":
        return _move(0.0, -MoveSpeed)

    if name == "strafe_right":
        return _move(0.0, MoveSpeed)

    if name == "jump":
        # jump as move-control (one-tick pulse if your server clears it)
        return _move(0.0, 0.0, jump=True)

    if name == "sneak":
        # sneak as move-control (hold if your server holds move for N ticks)
        return _move(0.0, 0.0, sneak=True)

    # Look --------------------------------------------------------
    def _look(yaw_delta: float, pitch_delta: float):
        return BuildAction(
            "look",
            {"look": {"yaw_delta": yaw_delta, "pitch_delta": pitch_delta, "turn_speed": 1.0}},
            seq,
        )

    if name == "look_left_small":
        return _look(-LookStep, 0.0)

    if name == "look_right_small":
        return _look(LookStep, 0.0)

    if name == "look_up_small":
        return _look(0.0, -LookStep)

    if name == "look_down_small":
        return _look(0.0, LookStep)

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
