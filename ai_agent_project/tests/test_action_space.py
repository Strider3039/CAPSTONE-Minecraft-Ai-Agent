import unittest
import sys
import os
import pathlib

# Add project root to PYTHONPATH
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ai.src.policy.action_space import (
    ACTIONS,
    NUM_ACTIONS,
    ActionIndex,
    ActionName,
    ToMinecraftControls
)


class TestActionSpace(unittest.TestCase):

    def ValidateActionMessage(self, msg):
        self.assertIn("proto", msg)
        self.assertIn("kind", msg)
        self.assertIn("seq", msg)
        self.assertIn("timestamp", msg)
        self.assertIn("action_id", msg)
        self.assertIn("payload", msg)
        self.assertEqual(msg["proto"], "1")
        self.assertEqual(msg["kind"], "action")

    def test_ActionCount(self):
        self.assertEqual(len(ACTIONS), NUM_ACTIONS)

    def test_ActionIndexName(self):
        for i, name in enumerate(ACTIONS):
            self.assertEqual(ActionIndex(name), i)
            self.assertEqual(ActionName(i), name)

    def test_Noop(self):
        msg = ToMinecraftControls(ActionIndex("noop"), seq=1)
        self.ValidateActionMessage(msg)
        self.assertEqual(msg["action_id"], "noop")
        self.assertEqual(msg["payload"], {})

    def test_MoveForward(self):
        msg = ToMinecraftControls(ActionIndex("move_forward"), seq=2)
        self.ValidateActionMessage(msg)
        move = msg["payload"]["move"]
        self.assertEqual(move["forward"], 1.0)
        self.assertEqual(move["strafe"], 0.0)

    def test_MoveBack(self):
        msg = ToMinecraftControls(ActionIndex("move_back"), seq=3)
        self.ValidateActionMessage(msg)
        move = msg["payload"]["move"]
        self.assertEqual(move["forward"], -1.0)
        self.assertEqual(move["strafe"], 0.0)

    def test_StrafeLeft(self):
        msg = ToMinecraftControls(ActionIndex("strafe_left"), seq=4)
        self.ValidateActionMessage(msg)
        move = msg["payload"]["move"]
        self.assertEqual(move["strafe"], -1.0)

    def test_StrafeRight(self):
        msg = ToMinecraftControls(ActionIndex("strafe_right"), seq=5)
        self.ValidateActionMessage(msg)
        move = msg["payload"]["move"]
        self.assertEqual(move["strafe"], 1.0)

    def test_Jump(self):
        msg = ToMinecraftControls(ActionIndex("jump"), seq=6)
        self.ValidateActionMessage(msg)
        self.assertEqual(msg["payload"], {"jump": True})

    def test_LookLeftSmall(self):
        msg = ToMinecraftControls(ActionIndex("look_left_small"), seq=7)
        self.ValidateActionMessage(msg)
        look = msg["payload"]["look"]
        self.assertLess(look["dYaw"], 0)
        self.assertEqual(look["dPitch"], 0.0)

    def test_LookRightSmall(self):
        msg = ToMinecraftControls(ActionIndex("look_right_small"), seq=8)
        self.ValidateActionMessage(msg)
        look = msg["payload"]["look"]
        self.assertGreater(look["dYaw"], 0)
        self.assertEqual(look["dPitch"], 0.0)

    def test_LookUpSmall(self):
        msg = ToMinecraftControls(ActionIndex("look_up_small"), seq=9)
        self.ValidateActionMessage(msg)
        look = msg["payload"]["look"]
        self.assertEqual(look["dYaw"], 0.0)
        self.assertLess(look["dPitch"], 0)

    def test_LookDownSmall(self):
        msg = ToMinecraftControls(ActionIndex("look_down_small"), seq=10)
        self.ValidateActionMessage(msg)
        look = msg["payload"]["look"]
        self.assertEqual(look["dYaw"], 0.0)
        self.assertGreater(look["dPitch"], 0)

    def test_InvalidIndexRaises(self):
        with self.assertRaises(IndexError):
            ToMinecraftControls(9999, seq=0)

    def test_SchemaExists(self):
        # Get absolute path to schema file
        test_file = pathlib.Path(__file__).resolve()
        project_root = test_file.parents[1] # ai_agent_project
        schema_path = project_root / "shared" / "schemas" / "action.schema.json"
        self.assertTrue(schema_path.exists())


if __name__ == "__main__":
    unittest.main()
