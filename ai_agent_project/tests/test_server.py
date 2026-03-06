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

import ai.src.app.server as server


# ------------------------------------------------------------
# Fully async mock WebSocket
# ------------------------------------------------------------
class DummyWS:
    """
    Async-iterable WebSocket mock used for testing server.Handle().
    """

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
# TEST 1 — start_new_episode behavior
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_start_new_episode(tmp_path, monkeypatch):
    """
    Verifies:
     - episode increments
     - episode_state.json is written
     - episode_start event sent
    """

    # Redirect server storage
    fake_shared = tmp_path / "shared"
    fake_shared.mkdir()

    monkeypatch.setattr(server, "sharedDir", fake_shared)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", fake_shared / "episode_state.json")

    # Reset global episode counter
    monkeypatch.setattr(server, "episode", 0)

    ws = DummyWS([])

    # obs_q/obs_state=None so synthetic episode_start is not enqueued; obs_drop_policy unused
    await server.start_new_episode(ws, None, None, "oldest")

    assert server.episode == 1

    # Check outgoing message
    assert len(ws.sent_messages) == 1
    msg = ws.sent_messages[0]
    assert msg["proto"] == "1"
    assert msg["kind"] == "episode_start"
    assert msg["payload"]["episode_start"]["reason"] == "episode_1_start"

    # Check file written
    saved = json.loads((fake_shared / "episode_state.json").read_text())
    assert saved["episode"] == 1


# ------------------------------------------------------------
# TEST 2 — episode_end triggers SECOND start_new_episode
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_episode_end_triggers_new_episode(tmp_path, monkeypatch):
    """
    Simulate:
        - server.Handle() starts initial episode
        - then WS sends {"kind": "episode_end"}
    Expect:
        start_new_episode called *twice* total.
    """

    # Fake shared folder
    fake_shared = tmp_path / "shared"
    fake_shared.mkdir()

    monkeypatch.setattr(server, "sharedDir", fake_shared)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", fake_shared / "episode_state.json")

    # Fake config for Handle()
    fake_cfg = MagicMock()
    fake_cfg.bridge = {
        "queues": {"obs_max": 8, "act_max": 8},
        "server": {"host": "127.0.0.1", "port": 8765},
        "metrics": {}
    }
    fake_cfg.runtime = {}

    # Patch LoadConfig so Handle() loads our fake config
    monkeypatch.setattr(server, "LoadConfig", lambda env=None: fake_cfg)

    # Prevent full RL stack creation
    monkeypatch.setattr(server, "build_policy_from_config", lambda cfg: MagicMock())

    # Patch background loops so they do nothing
    monkeypatch.setattr(server, "MetricsLoop", lambda *args, **kwargs: asyncio.sleep(999999))
    monkeypatch.setattr(server, "HeartBeatLoop", lambda *args, **kwargs: asyncio.sleep(999999))
    monkeypatch.setattr(server, "PolicyWorker", lambda *args, **kwargs: asyncio.sleep(999999))

    # Mock start_new_episode so we count calls
    mock_start = AsyncMock()
    monkeypatch.setattr(server, "start_new_episode", mock_start)

    # Message: client says episode ended
    episode_end_json = json.dumps({
        "proto": "1",
        "kind": "episode_end",
        "seq": 100,
        "timestamp": 555.0,
        "payload": {"episode_end": {"reason": "death"}}
    })

    # WebSocket containing *one* event: episode_end
    ws = DummyWS([episode_end_json])

    # Handle should:
    #   - call start_new_episode once at connection
    #   - then again when episode_end arrives
    with pytest.raises(asyncio.CancelledError):
        await server.Handle(ws)

    assert mock_start.await_count == 2, \
        f"Expected 2 calls (initial + death). Got {mock_start.await_count}"
