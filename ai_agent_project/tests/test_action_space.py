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
import unittest
import sys
import os
import pathlib
import json
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


class TestActionSpace(unittest.TestCase):

    def ValidateActionMessage(self, msg):
        """Check the action envelope and payload against the JSON schema."""
        try:
            validate(instance=msg, schema=ActionSchema)
        except Exception as e:
            raise AssertionError(f"Schema validation failed:\n{e}")

        self.assertEqual(msg["proto"], "1")
        self.assertEqual(msg["kind"], "action")
        self.assertIn("seq", msg)
        self.assertIn("timestamp", msg)
        self.assertIn("action_id", msg)
        self.assertIn("payload", msg)

    def test_ActionCount(self):
        """NUM_ACTIONS should stay in sync with the ACTIONS list length."""
        self.assertEqual(NUM_ACTIONS, len(ACTIONS))

    def test_IndexNameRoundTrip(self):
        """Every action name should map to its index and back again."""
        for i, name in enumerate(ACTIONS):
            self.assertEqual(ActionIndex(name), i)
            self.assertEqual(ActionName(i), name)

    def test_Noop(self):
        """noop should produce a valid action with neutral look and move payloads."""
        msg = ToMinecraftControls(ActionIndex("noop"), seq=1)
        self.ValidateActionMessage(msg)
        self.assertEqual(msg["action_id"], "noop")
        self.assertIn("look", msg["payload"])
        self.assertIn("move", msg["payload"])

    def test_MoveForward(self):
        """move_forward should set forward to +1.0 in the move payload."""
        msg = ToMinecraftControls(ActionIndex("move_forward"), seq=2)
        self.ValidateActionMessage(msg)
        m = msg["payload"]["move"]
        self.assertEqual(m["forward"], 1.0)

    def test_MoveBack(self):
        """move_back should set forward to -1.0."""
        msg = ToMinecraftControls(ActionIndex("move_back"), seq=3)
        self.ValidateActionMessage(msg)
        m = msg["payload"]["move"]
        self.assertEqual(m["forward"], -1.0)

    def test_StrafeLeft(self):
        """strafe_left should set strafe to -1.0."""
        msg = ToMinecraftControls(ActionIndex("strafe_left"), seq=4)
        self.ValidateActionMessage(msg)
        m = msg["payload"]["move"]
        self.assertEqual(m["strafe"], -1.0)

    def test_StrafeRight(self):
        """strafe_right should set strafe to +1.0."""
        msg = ToMinecraftControls(ActionIndex("strafe_right"), seq=5)
        self.ValidateActionMessage(msg)
        m = msg["payload"]["move"]
        self.assertEqual(m["strafe"], 1.0)

    def test_Jump(self):
        """jump should set move.jump to True."""
        msg = ToMinecraftControls(ActionIndex("jump"), seq=6)
        self.ValidateActionMessage(msg)
        self.assertEqual(msg["payload"]["move"]["jump"], True)

    def test_LookLeft(self):
        """look_left_small should produce a negative yaw_delta."""
        msg = ToMinecraftControls(ActionIndex("look_left_small"), seq=7)
        self.ValidateActionMessage(msg)
        self.assertLess(msg["payload"]["look"]["yaw_delta"], 0)

    def test_LookRight(self):
        """look_right_small should produce a positive yaw_delta."""
        msg = ToMinecraftControls(ActionIndex("look_right_small"), seq=8)
        self.ValidateActionMessage(msg)
        self.assertGreater(msg["payload"]["look"]["yaw_delta"], 0)

    def test_InvalidIndex(self):
        """An out-of-range action index should raise instead of inventing a message."""
        with self.assertRaises(Exception):
            ToMinecraftControls(999, seq=0)

    def test_SchemaFileExists(self):
        """The action JSON schema file should be present under schemas/."""
        root = pathlib.Path(__file__).parents[1]
        schema_path = root / "schemas" / "action.schema.json"
        self.assertTrue(schema_path.exists())


if __name__ == "__main__":
    unittest.main()
