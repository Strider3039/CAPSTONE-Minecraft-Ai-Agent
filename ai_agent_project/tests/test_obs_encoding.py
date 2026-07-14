# -----------------------------------------------------------------------------
# Observation encoding tests
# -----------------------------------------------------------------------------
import numpy as np
import os
import sys
import pytest

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ai.policy.obs_encoding import EncodeObservation, OBS_DIM


def test_basic_encoding():
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
    assert vec.shape == (OBS_DIM,)
    assert vec.dtype == np.float32

    assert vec[0] == pytest.approx(1.0)     # x
    assert vec[1] == pytest.approx(64.0)    # y
    assert vec[2] == pytest.approx(-5.0)    # z
    # yaw and pitch encoded as sin/cos
    assert vec[3] == pytest.approx(1.0)     # sin(90)
    assert vec[4] == pytest.approx(0.0)     # cos(90)
    assert vec[5] == pytest.approx(0.0)     # sin(0)
    assert vec[6] == pytest.approx(1.0)     # cos(0)

    # first ray: hit at 5m → hit=1, dist_norm=0.5
    assert vec[7] == pytest.approx(1.0)     # ray 0 hit
    assert vec[8] == pytest.approx(0.5)     # ray 0 dist_norm
    # second ray: no hit
    assert vec[9] == pytest.approx(0.0)     # ray 1 hit
    assert vec[10] == pytest.approx(0.0)    # ray 1 dist_norm

    # padding rays we didn't send should be zero
    assert vec[37] == pytest.approx(0.0)
    assert vec[38] == pytest.approx(0.0)

    # Front clear
    assert vec[39] == pytest.approx(1.0)    # front_clear

    # grounded, not colliding, making progress
    assert vec[40] == pytest.approx(1.0)    # is_grounded
    assert vec[41] == pytest.approx(0.0)    # is_colliding
    assert vec[42] == pytest.approx(0.0)    # no_progress

    # time_of_day 6000 → 0.25; weather "rain" → [0, 1, 0]
    assert vec[43] == pytest.approx(0.25)   # time_of_day
    assert vec[44] == pytest.approx(0.0)    # clear
    assert vec[45] == pytest.approx(1.0)    # rain
    assert vec[46] == pytest.approx(0.0)    # thunder
