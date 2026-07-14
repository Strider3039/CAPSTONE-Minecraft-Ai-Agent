# -----------------------------------------------------------------------------
# Runtime overlay defaults tests
# -----------------------------------------------------------------------------
import importlib
import os
import sys

import pytest

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def test_build_default_runtime_overlay_matches_bridge_ui_shape():
    """Default overlay should normalize control_mode and carry through DQN/reward fields the UI expects."""
    server = importlib.import_module("bridge.server")

    runtime_cfg = {
        "control_mode": "server-bot",
        "policy": {
            "dqn": {
                "epsilon_start": 0.42,
            },
            "reward": {
                "survival_reward": 0.01,
                "step_penalty": -0.02,
                "move_scale": 1.5,
                "max_move_reward": 0.2,
                "no_progress_penalty": -0.03,
                "front_clear_bonus": 0.015,
                "item_pickup_reward": 0.25,
                "max_steps_per_episode": 321,
                "blocks": {"minecraft:diamond_ore": 1.0},
                "mobs": {"minecraft:zombie": 0.5},
            },
        },
    }

    overlay = server.build_default_runtime_overlay(runtime_cfg)

    assert overlay["control_mode"] == "SERVER_BOT"
    assert overlay["policy"]["dqn"]["epsilon_start"] == pytest.approx(0.42)

    reward = overlay["policy"]["reward"]
    assert reward["survival_reward"] == pytest.approx(0.01)
    assert reward["step_penalty"] == pytest.approx(-0.02)
    assert reward["move_scale"] == pytest.approx(1.5)
    assert reward["max_move_reward"] == pytest.approx(0.2)
    assert reward["no_progress_penalty"] == pytest.approx(-0.03)
    assert reward["front_clear_bonus"] == pytest.approx(0.015)
    assert reward["item_pickup_reward"] == pytest.approx(0.25)
    assert reward["max_steps_per_episode"] == 321
    assert reward["blocks"] == {"minecraft:diamond_ore": 1.0}
    assert reward["mobs"] == {"minecraft:zombie": 0.5}
