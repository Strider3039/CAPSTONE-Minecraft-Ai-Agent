import math
import unittest
import numpy as np
import os
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ai.src.policy.obs_encoding import EncodeObservation, OBS_DIM

class TestObsEncoding(unittest.TestCase):
    def test_basic_encoding(self):
        maxRayDist = 10.0

        obsMsg = {
            "proto": "1",
            "kind": "observation",
            "payload": {
                "pose": {
                    "x": 1.0,
                    "y": 64.0,
                    "z": -5.0,
                    "yaw": 90.0,
                    "pitch": 0.0,
                },
                "rays": [
                    {"hit": True, "dist": 5.0},
                    {"hit": False, "dist": 0.0},
                ],
                "front_clear": True,
                "collision": {
                    "is_grounded": True,
                    "is_colliding": False,
                    "no_progress": False
                },
                "world": {
                    "time_of_day": 6000,
                    "weather": "rain",
                    "biome": "minecraft:plains",
                },
                "inventory": {
                    "selected_slot": 0,
                    "hotbar": [],
                },
            },
        }

        vec = EncodeObservation(obsMsg, maxRayDist)

        # Shape and data type
        self.assertEqual(vec.shape, (OBS_DIM,))
        self.assertEqual(vec.dtype, np.float32)

        # Pose
        self.assertAlmostEqual(vec[0], 1.0)     # x
        self.assertAlmostEqual(vec[1], 64.0)    # y
        self.assertAlmostEqual(vec[2], -5.0)    # z
        # Yaw/Pitch (sin/cos)
        self.assertAlmostEqual(vec[3], 1.0)     # sin(90)
        self.assertAlmostEqual(vec[4], 0.0)     # cos(90)
        self.assertAlmostEqual(vec[5], 0.0)     # sin(0)
        self.assertAlmostEqual(vec[6], 1.0)     # cos(0)

        # Rays
        # Ray 0: hit=True, dist=5.0; hit=1, dist_norm=0.5
        self.assertAlmostEqual(vec[7], 1.0)     # ray 0 hit
        self.assertAlmostEqual(vec[8], 0.5)     # ray 0 dist_norm
        # Ray 1: hit=False, dist=0.0; hit=0, dist_norm=0.0
        self.assertAlmostEqual(vec[9], 0.0)     # ray 1 hit
        self.assertAlmostEqual(vec[10], 0.0)    # ray 1 dist_norm

        # Unused rays should be zero. Check a couple of them.
        self.assertAlmostEqual(vec[37], 0.0)    
        self.assertAlmostEqual(vec[38], 0.0)

        # Front clear
        self.assertAlmostEqual(vec[39], 1.0)    # front_clear

        # Collision flags
        # is_grounded=True, is_colliding=False, no_progress=False
        self.assertAlmostEqual(vec[40], 1.0)    # is_grounded
        self.assertAlmostEqual(vec[41], 0.0)    # is_colliding
        self.assertAlmostEqual(vec[42], 0.0)    # no_progress

        # World
        # Time of day = 6000 -> 6000 / 24000 = 0.25
        self.assertAlmostEqual(vec[43], 0.25)   # time_of_day
        # Weather = "rain" -> [0, 1, 0]
        self.assertAlmostEqual(vec[44], 0.0)    # clear
        self.assertAlmostEqual(vec[45], 1.0)    # rain
        self.assertAlmostEqual(vec[46], 0.0)    # thunder

if __name__ == "__main__":
    unittest.main()

