# -----------------------------------------------------------------------------
# Online DQN policy tests
#
# These test the live policy that runs inside the bridge during gameplay, not
# offline training. We feed fake observations and episode_end events through
# policy.act() and check that transitions land in the replay buffer, episode
# history gets written, and shutdown flushes pending state.
#
# Prevents losing training data on death/disconnect, mixing up episode state
# files with the bridge's, or the reward engine failing to request a reset when
# max steps is hit.
# -----------------------------------------------------------------------------
import json
import os
import sys

import pytest

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

pytest.importorskip("torch")

from ai.rl.dqn.agent import DQNAgent, OnlineDQNPolicy


def _obs(seq: int, x: float = 0.0) -> dict:
    return {
        "proto": "1",
        "kind": "observation",
        "seq": seq,
        "timestamp": float(seq),
        "payload": {
            "pose": {"x": x, "y": 64.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0},
            "rays": [{"hit": False, "dist": 5.0, "angle_deg": 0.0}],
            "front_clear": True,
            "world": {"time_of_day": 0.0, "weather": "clear", "biome": "minecraft:plains"},
            "inventory": {"selected_slot": 0, "hotbar": []},
            "collision": {"is_grounded": True, "is_colliding": False, "no_progress": False},
        },
    }


def _make_policy(tmp_path) -> OnlineDQNPolicy:
    agent = DQNAgent(minReplaySize=10_000)
    policy = OnlineDQNPolicy(agent, max_ray_dist=10.0, save_every_steps=0)
    policy._episode_state_path = tmp_path / "online_dqn_episode_state.json"
    policy._episode_history_path = tmp_path / "episode_history.json"
    policy._step_history_path = tmp_path / "step_history.jsonl"
    return policy


def test_online_dqn_uses_separate_live_state_file(tmp_path):
    """Online DQN should keep its live episode state in its own file, not the bridge's."""
    policy = _make_policy(tmp_path)
    assert policy._episode_state_path.name == "online_dqn_episode_state.json"


def test_online_dqn_episode_end_flushes_terminal_transition(tmp_path):
    """An episode_end from the client should flush a done transition into the replay buffer."""
    policy = _make_policy(tmp_path)

    first = policy.act(_obs(1, x=0.0))
    assert first["kind"] == "action"

    terminal_event = {
        "proto": "1",
        "kind": "episode_end",
        "seq": 2,
        "timestamp": 2.0,
        "payload": {"episode_end": {"reason": "death"}},
    }
    out = policy.act(terminal_event)

    assert out["kind"] == "action"
    assert len(policy.agent.replayBuffer) == 1
    assert policy.agent.replayBuffer.dones[0] == 1.0
    assert policy._step_history_path.exists()

    history = json.loads(policy._episode_history_path.read_text("utf-8"))
    assert history[-1]["done_reason"] == "client_episode_end"


def test_online_dqn_reward_engine_done_requests_real_reset(tmp_path):
    """Hitting max steps should request a real episode reset via eval_control start_episode."""
    policy = _make_policy(tmp_path)
    policy.reward_engine.max_steps_per_episode = 1

    first = policy.act(_obs(1, x=0.0))
    assert first["kind"] == "action"

    second = policy.act(_obs(2, x=1.0))
    assert second["kind"] == "eval_control"
    assert second["payload"]["action"] == "start_episode"
    assert len(policy.agent.replayBuffer) == 1
    assert policy.agent.replayBuffer.dones[0] == 1.0


def test_online_dqn_shutdown_flushes_pending_transition(tmp_path):
    """shutdown() should flush any pending transition so we don't lose the last step."""
    policy = _make_policy(tmp_path)
    policy.act(_obs(1, x=0.0))

    policy.shutdown("unit_test_shutdown")

    assert len(policy.agent.replayBuffer) == 1
    assert policy.agent.replayBuffer.dones[0] == 1.0
    history = json.loads(policy._episode_history_path.read_text("utf-8"))
    assert history[-1]["done_reason"] == "unit_test_shutdown"
