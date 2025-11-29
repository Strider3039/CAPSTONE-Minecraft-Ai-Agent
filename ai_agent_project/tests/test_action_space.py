import unittest
import sys
import os
import pathlib
import json
from jsonschema import validate

# Add project root
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ai.src.policy.action_space import (
    ACTIONS,
    NUM_ACTIONS,
    ActionIndex,
    ActionName,
    ToMinecraftControls,
    ActionSchema,
)


class TestActionSpace(unittest.TestCase):

    def ValidateActionMessage(self, msg):
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
        self.assertEqual(NUM_ACTIONS, len(ACTIONS))

    def test_IndexNameRoundTrip(self):
        for i, name in enumerate(ACTIONS):
            self.assertEqual(ActionIndex(name), i)
            self.assertEqual(ActionName(i), name)

    def test_Noop(self):
        msg = ToMinecraftControls(ActionIndex("noop"), seq=1)
        self.ValidateActionMessage(msg)
        self.assertEqual(msg["action_id"], "look")
        self.assertIn("look", msg["payload"])

    def test_MoveForward(self):
        msg = ToMinecraftControls(ActionIndex("move_forward"), seq=2)
        self.ValidateActionMessage(msg)
        m = msg["payload"]["move"]
        self.assertEqual(m["forward"], 1.0)

    def test_MoveBack(self):
        msg = ToMinecraftControls(ActionIndex("move_back"), seq=3)
        self.ValidateActionMessage(msg)
        m = msg["payload"]["move"]
        self.assertEqual(m["forward"], -1.0)

    def test_StrafeLeft(self):
        msg = ToMinecraftControls(ActionIndex("strafe_left"), seq=4)
        self.ValidateActionMessage(msg)
        m = msg["payload"]["move"]
        self.assertEqual(m["strafe"], -1.0)

    def test_StrafeRight(self):
        msg = ToMinecraftControls(ActionIndex("strafe_right"), seq=5)
        self.ValidateActionMessage(msg)
        m = msg["payload"]["move"]
        self.assertEqual(m["strafe"], 1.0)

    def test_Jump(self):
        msg = ToMinecraftControls(ActionIndex("jump"), seq=6)
        self.ValidateActionMessage(msg)
        # schema: jump must be boolean
        self.assertEqual(msg["payload"]["jump"], True)

    def test_LookLeft(self):
        msg = ToMinecraftControls(ActionIndex("look_left_small"), seq=7)
        self.ValidateActionMessage(msg)
        self.assertLess(msg["payload"]["look"]["dYaw"], 0)

    def test_LookRight(self):
        msg = ToMinecraftControls(ActionIndex("look_right_small"), seq=8)
        self.ValidateActionMessage(msg)
        self.assertGreater(msg["payload"]["look"]["dYaw"], 0)

    def test_InvalidIndex(self):
        with self.assertRaises(Exception):
            ToMinecraftControls(999, seq=0)

    def test_SchemaFileExists(self):
        root = pathlib.Path(__file__).parents[1]
        schema_path = root / "shared" / "schemas" / "action.schema.json"
        self.assertTrue(schema_path.exists())


if __name__ == "__main__":
    unittest.main()
