# -----------------------------------------------------------------------------
# Observation encoding tests
#
# The policy doesn't read raw JSON. It needs a fixed-size float vector. These
# tests check that EncodeObservation turns a realistic observation message into
# the right shape and sane values for the neural net.
#
# Catches regressions when someone adds a new observation field or changes ray
# encoding and the network suddenly gets the wrong input dimension or garbage data.
# -----------------------------------------------------------------------------
import math
import unittest
import numpy as np
import os
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ai.policy.obs_encoding import EncodeObservation, OBS_DIM

class TestObsEncoding(unittest.TestCase):
    def test_basic_encoding(self):
        """A realistic observation should encode to OBS_DIM floats with expected pose/ray/world values."""
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

        # vector shape and dtype
        self.assertEqual(vec.shape, (OBS_DIM,))
        self.assertEqual(vec.dtype, np.float32)

        self.assertAlmostEqual(vec[0], 1.0)     # x
        self.assertAlmostEqual(vec[1], 64.0)    # y
        self.assertAlmostEqual(vec[2], -5.0)    # z
        # yaw and pitch encoded as sin/cos
        self.assertAlmostEqual(vec[3], 1.0)     # sin(90)
        self.assertAlmostEqual(vec[4], 0.0)     # cos(90)
        self.assertAlmostEqual(vec[5], 0.0)     # sin(0)
        self.assertAlmostEqual(vec[6], 1.0)     # cos(0)

        # first ray: hit at 5m → hit=1, dist_norm=0.5
        self.assertAlmostEqual(vec[7], 1.0)     # ray 0 hit
        self.assertAlmostEqual(vec[8], 0.5)     # ray 0 dist_norm
        # second ray: no hit
        self.assertAlmostEqual(vec[9], 0.0)     # ray 1 hit
        self.assertAlmostEqual(vec[10], 0.0)    # ray 1 dist_norm

        # padding rays we didn't send should be zero
        self.assertAlmostEqual(vec[37], 0.0)    
        self.assertAlmostEqual(vec[38], 0.0)

        # Front clear
        self.assertAlmostEqual(vec[39], 1.0)    # front_clear

        # grounded, not colliding, making progress
        self.assertAlmostEqual(vec[40], 1.0)    # is_grounded
        self.assertAlmostEqual(vec[41], 0.0)    # is_colliding
        self.assertAlmostEqual(vec[42], 0.0)    # no_progress

        # time_of_day 6000 → 0.25; weather "rain" → [0, 1, 0]
        self.assertAlmostEqual(vec[43], 0.25)   # time_of_day
        self.assertAlmostEqual(vec[44], 0.0)    # clear
        self.assertAlmostEqual(vec[45], 1.0)    # rain
        self.assertAlmostEqual(vec[46], 0.0)    # thunder

if __name__ == "__main__":
    unittest.main()

