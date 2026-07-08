# -----------------------------------------------------------------------------
# Control mode and role enforcement tests
#
# The bridge has PLAYER vs SERVER_BOT modes, and each mode only accepts certain
# WebSocket roles (client vs server). These tests make sure the wrong peer can't
# take over, and that switching modes via config_update closes incompatible connections.
#
# Stops bugs where a dedicated server bot connects in player mode, a client stays
# active after you flip to server-bot, or role checks get bypassed after hot-reload.
#
# Requires: pytest, pytest-asyncio, torch.
# -----------------------------------------------------------------------------

import sys
import pathlib

FILE = pathlib.Path(__file__).resolve()
ROOT = FILE.parents[1]  # ai_agent_project/
sys.path.insert(0, str(ROOT))

import json
import asyncio
import pytest
from unittest.mock import MagicMock

pytest.importorskip("torch")

from websockets.exceptions import ConnectionClosedOK

import bridge.server as server


class DummyWS:
    """Async-iterable WS mock with minimal attributes used by server.Handle()."""

    def __init__(self, incoming, disconnect_after=False):
        self.sent_messages = []
        self._incoming = list(incoming)
        self._index = 0
        self._disconnect_after = disconnect_after
        self._closed = False

        # server logs these if present
        self.remote_address = ("127.0.0.1", 12345)

    async def send(self, data):
        if isinstance(data, (bytes, bytearray)):
            data = data.decode("utf-8")
        try:
            self.sent_messages.append(json.loads(data))
        except Exception:
            self.sent_messages.append(data)

    async def close(self, code=None, reason=None):
        self._closed = True

    @property
    def closed(self):
        return self._closed

    def __aiter__(self):
        return self

    async def __anext__(self):
        if self._index < len(self._incoming):
            msg = self._incoming[self._index]
            self._index += 1
            await asyncio.sleep(0)  # yield to background tasks
            return msg
        if self._disconnect_after:
            raise ConnectionClosedOK(None, "test disconnect")
        raise asyncio.CancelledError()


def _fake_cfg(control_mode: str, hello_timeout_s: float = 0.5):
    cfg = MagicMock()
    cfg.bridge = {
        "queues": {"obs_max": 8, "act_max": 8, "obs_drop_policy": "oldest"},
        "server": {"host": "127.0.0.1", "port": 8765},
        "metrics": {"enabled": False},
    }
    cfg.runtime = {
        "control_mode": control_mode,
        "hello_timeout_s": hello_timeout_s,
        "validate_actions": False,
        "policy": {"tick_hz": 20},
    }
    return cfg


def _hello(role: str):
    return json.dumps({"proto": "1", "kind": "hello", "role": role})


def _config_update(mode: str):
    return json.dumps({"proto": "1", "kind": "config_update", "payload": {"control_mode": mode}})


@pytest.mark.asyncio
async def test_player_mode_rejects_server_role_as_active(tmp_path, monkeypatch):
    """When control_mode=PLAYER, role=server must NOT start workers (config-only)."""
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "episode_state.json")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg("PLAYER")

    build_policy = MagicMock()
    monkeypatch.setattr(server, "build_policy_from_config", build_policy)
    # keep background loops inert if somehow started
    monkeypatch.setattr(server, "MetricsLoop", lambda *a, **k: asyncio.sleep(999999))
    monkeypatch.setattr(server, "HeartBeatLoop", lambda *a, **k: asyncio.sleep(999999))
    monkeypatch.setattr(server, "PolicyWorker", lambda *a, **k: asyncio.sleep(999999))

    ws = DummyWS([_hello("server")], disconnect_after=True)
    await server.Handle(ws, cfg)

    assert not build_policy.called, "server-role in PLAYER must not start policy/workers"


@pytest.mark.asyncio
async def test_server_role_can_flip_to_server_bot_via_config_update(tmp_path, monkeypatch):
    """
    Start in PLAYER, connect as role=server (config-only), then send config_update SERVER_BOT.
    The bridge must upgrade and start workers.
    """
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "episode_state.json")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg("PLAYER")

    build_policy = MagicMock(return_value=MagicMock(act=MagicMock(return_value={"payload": {}})))
    monkeypatch.setattr(server, "build_policy_from_config", build_policy)
    monkeypatch.setattr(server, "MetricsLoop", lambda *a, **k: asyncio.sleep(999999))
    monkeypatch.setattr(server, "HeartBeatLoop", lambda *a, **k: asyncio.sleep(999999))
    monkeypatch.setattr(server, "PolicyWorker", lambda *a, **k: asyncio.sleep(999999))

    ws = DummyWS([_hello("server"), _config_update("SERVER_BOT")], disconnect_after=True)
    await server.Handle(ws, cfg)

    assert build_policy.called, "after config_update to SERVER_BOT, server-role should start workers"


@pytest.mark.asyncio
async def test_client_role_closed_when_switching_to_server_bot(tmp_path, monkeypatch):
    """
    In PLAYER, role=client is active. If config_update switches to SERVER_BOT, it must close the ws.
    """
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "episode_state.json")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg("PLAYER")

    monkeypatch.setattr(server, "build_policy_from_config", MagicMock(return_value=MagicMock(act=MagicMock(return_value={"payload": {}}))))
    monkeypatch.setattr(server, "MetricsLoop", lambda *a, **k: asyncio.sleep(999999))
    monkeypatch.setattr(server, "HeartBeatLoop", lambda *a, **k: asyncio.sleep(999999))
    monkeypatch.setattr(server, "PolicyWorker", lambda *a, **k: asyncio.sleep(999999))

    ws = DummyWS([_hello("client"), _config_update("SERVER_BOT")], disconnect_after=False)

    # Handle should return after it closes the ws due to wrong_role
    try:
        await asyncio.wait_for(server.Handle(ws, cfg), timeout=1.0)
    except asyncio.TimeoutError:
        pytest.fail("server.Handle() did not terminate after closing wrong-role client on mode change")

    assert ws.closed, "ws must be closed when client becomes disallowed after mode switch"

