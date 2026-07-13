# -----------------------------------------------------------------------------
# Action space tests
#
# These check that every discrete action the DQN can pick maps to a valid bridge
# message the Minecraft mod understands. We verify name/index round-trips, schema
# compliance, and that each action sets the right move/look controls.
#
# Without these, a small change to the ACTIONS list could silently send the wrong
# command (e.g. index 3 triggers jump instead of strafe_left), or produce JSON
# the mod rejects at runtime.
# -----------------------------------------------------------------------------
import sys
import os
import pathlib
import pytest
from jsonschema import validate

# Add project root
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ai.policy.action_space import (
    ACTIONS,
    NUM_ACTIONS,
    ActionIndex,
    ActionName,
    ToMinecraftControls,
    ActionSchema,
)


def ValidateActionMessage(msg):
    """Check the action envelope and payload against the JSON schema."""
    try:
        validate(instance=msg, schema=ActionSchema)
    except Exception as e:
        raise AssertionError(f"Schema validation failed:\n{e}")

    assert msg["proto"] == "1"
    assert msg["kind"] == "action"
    assert "seq" in msg
    assert "timestamp" in msg
    assert "action_id" in msg
    assert "payload" in msg


def test_ActionCount():
    """NUM_ACTIONS should stay in sync with the ACTIONS list length."""
    assert NUM_ACTIONS == len(ACTIONS)


def test_IndexNameRoundTrip():
    """Every action name should map to its index and back again."""
    for i, name in enumerate(ACTIONS):
        assert ActionIndex(name) == i
        assert ActionName(i) == name


def test_Noop():
    """noop should produce a valid action with neutral look and move payloads."""
    msg = ToMinecraftControls(ActionIndex("noop"), seq=1)
    ValidateActionMessage(msg)
    assert msg["action_id"] == "noop"
    assert "look" in msg["payload"]
    assert "move" in msg["payload"]


def test_MoveForward():
    """move_forward should set forward to +1.0 in the move payload."""
    msg = ToMinecraftControls(ActionIndex("move_forward"), seq=2)
    ValidateActionMessage(msg)
    m = msg["payload"]["move"]
    assert m["forward"] == 1.0


def test_MoveBack():
    """move_back should set forward to -1.0."""
    msg = ToMinecraftControls(ActionIndex("move_back"), seq=3)
    ValidateActionMessage(msg)
    m = msg["payload"]["move"]
    assert m["forward"] == -1.0


def test_StrafeLeft():
    """strafe_left should set strafe to -1.0."""
    msg = ToMinecraftControls(ActionIndex("strafe_left"), seq=4)
    ValidateActionMessage(msg)
    m = msg["payload"]["move"]
    assert m["strafe"] == -1.0


def test_StrafeRight():
    """strafe_right should set strafe to +1.0."""
    msg = ToMinecraftControls(ActionIndex("strafe_right"), seq=5)
    ValidateActionMessage(msg)
    m = msg["payload"]["move"]
    assert m["strafe"] == 1.0


def test_Jump():
    """jump should set move.jump to True."""
    msg = ToMinecraftControls(ActionIndex("jump"), seq=6)
    ValidateActionMessage(msg)
    assert msg["payload"]["move"]["jump"] is True


def test_LookLeft():
    """look_left_small should produce a negative yaw_delta."""
    msg = ToMinecraftControls(ActionIndex("look_left_small"), seq=7)
    ValidateActionMessage(msg)
    assert msg["payload"]["look"]["yaw_delta"] < 0


def test_LookRight():
    """look_right_small should produce a positive yaw_delta."""
    msg = ToMinecraftControls(ActionIndex("look_right_small"), seq=8)
    ValidateActionMessage(msg)
    assert msg["payload"]["look"]["yaw_delta"] > 0


def test_InvalidIndex():
    """An out-of-range action index should raise instead of inventing a message."""
    with pytest.raises(Exception):
        ToMinecraftControls(999, seq=0)


def test_SchemaFileExists():
    """The action JSON schema file should be present under schemas/."""
    root = pathlib.Path(__file__).parents[1]
    schema_path = root / "schemas" / "action.schema.json"
    assert schema_path.exists()
