# -----------------------------------------------------------------------------
# Bridge episode lifecycle tests
#
# Focused tests for start_new_episode and what happens when the client sends
# episode_end. Uses a minimal DummyWS mock, not the full connection lifecycle suite.
#
# Catches episodes not incrementing, episode_state.json not being written,
# episode_start not sent to the client, or death not triggering a new episode
# (the second start_new_episode call after episode_end).
# -----------------------------------------------------------------------------
# ------------------------------------------------------------
# Make project root importable when running from tests/ folder
# ------------------------------------------------------------
import sys, pathlib

FILE = pathlib.Path(__file__).resolve()
ROOT = FILE.parents[1]    # ai_agent_project/
sys.path.insert(0, str(ROOT))

# ------------------------------------------------------------
# Imports
# ------------------------------------------------------------
import json
import asyncio
import pytest
from unittest.mock import MagicMock, AsyncMock, patch

import bridge.server as server


# ------------------------------------------------------------
# Fully async mock WebSocket
# ------------------------------------------------------------
class DummyWS:
    """Minimal fake WebSocket for driving Handle() in episode tests."""

    def __init__(self, incoming_messages):
        self.sent_messages = []
        self._incoming = incoming_messages
        self._index = 0

    async def send(self, data):
        if isinstance(data, (bytes, bytearray)):
            data = data.decode("utf-8")

        try:
            self.sent_messages.append(json.loads(data))
        except Exception:
            self.sent_messages.append(data)

    def __aiter__(self):
        return self

    async def __anext__(self):
        if self._index >= len(self._incoming):
            raise asyncio.CancelledError()
        msg = self._incoming[self._index]
        self._index += 1
        return msg


# ------------------------------------------------------------
# TEST 1: start_new_episode behavior
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_start_new_episode(tmp_path, monkeypatch):
    """Starting a new episode should bump the counter, write episode_state.json, and send episode_start."""
    # point episode storage at a temp folder
    fake_shared = tmp_path / "shared"
    fake_shared.mkdir()

    monkeypatch.setattr(server, "sharedDir", fake_shared)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", fake_shared / "episode_state.json")
    monkeypatch.setattr(server, "episode", 0)
    monkeypatch.setattr(server, "episode_start_time", None)
    monkeypatch.setattr(server, "episode", 0)
    monkeypatch.setattr(server, "episode_start_time", None)

    # start from a clean episode counter
    monkeypatch.setattr(server, "episode", 0)

    ws = DummyWS([])

    # obs queues are None here. We're only testing the episode_start side effects.
    await server.start_new_episode(ws, None, None, "oldest")

    assert server.episode == 1

    # should have sent one episode_start to the client
    assert len(ws.sent_messages) == 1
    msg = ws.sent_messages[0]
    assert msg["proto"] == "1"
    assert msg["kind"] == "episode_start"
    assert msg["payload"]["episode_start"]["reason"] == "episode_1_start"

    # episode counter should be persisted to disk
    saved = json.loads((fake_shared / "episode_state.json").read_text())
    assert saved["episode"] == 1


# ------------------------------------------------------------
# TEST 2: episode_end triggers SECOND start_new_episode
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_episode_end_triggers_new_episode(tmp_path, monkeypatch):
    """Handle() should call start_new_episode on hello, then again when the client reports episode_end."""
    # Fake shared folder
    fake_shared = tmp_path / "shared"
    fake_shared.mkdir()

    monkeypatch.setattr(server, "sharedDir", fake_shared)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", fake_shared / "episode_state.json")
    monkeypatch.setattr(server, "episode", 0)
    monkeypatch.setattr(server, "episode_start_time", None)
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})
    monkeypatch.setattr(server, "save_runtime_overlay", lambda overlay: None)

    # stub config so Handle() doesn't touch real files
    fake_cfg = MagicMock()
    fake_cfg.bridge = {
        "queues": {"obs_max": 8, "act_max": 8},
        "server": {"host": "127.0.0.1", "port": 8765},
        "metrics": {}
    }
    fake_cfg.runtime = {
        "control_mode": "PLAYER",
        "hello_timeout_s": 2.0,
        "validate_actions": False,
        "policy": {"tick_hz": 20},
    }

    # make Handle() use our stub config instead of loading from disk
    monkeypatch.setattr(server, "LoadConfig", lambda env=None: fake_cfg)

    # don't spin up a real DQN policy
    monkeypatch.setattr(server, "build_policy_from_config", lambda cfg: MagicMock())

    # background loops would just add noise
    monkeypatch.setattr(server, "MetricsLoop", lambda *args, **kwargs: asyncio.sleep(999999))
    monkeypatch.setattr(server, "HeartBeatLoop", lambda *args, **kwargs: asyncio.sleep(999999))
    monkeypatch.setattr(server, "PolicyWorker", lambda *args, **kwargs: asyncio.sleep(999999))

    # swap in a mock so we can count how many times episodes start
    mock_start = AsyncMock()
    monkeypatch.setattr(server, "start_new_episode", mock_start)

    hello_json = json.dumps({
        "proto": "1",
        "kind": "hello",
        "role": "client",
        "control_mode": "PLAYER",
    })
    # client says they died
    episode_end_json = json.dumps({
        "proto": "1",
        "kind": "episode_end",
        "seq": 100,
        "timestamp": 555.0,
        "payload": {"episode_end": {"reason": "death"}}
    })

    # script: hello, then episode_end
    ws = DummyWS([hello_json, episode_end_json])

    with pytest.raises(asyncio.CancelledError):
        await server.Handle(ws, fake_cfg)

    assert mock_start.await_count == 2, \
        f"Expected 2 calls (initial + death). Got {mock_start.await_count}"
