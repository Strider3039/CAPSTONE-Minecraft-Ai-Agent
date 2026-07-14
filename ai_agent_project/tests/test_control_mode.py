# -----------------------------------------------------------------------------
# Control mode and role enforcement tests
# -----------------------------------------------------------------------------

import os
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import json
import asyncio
import pytest
from unittest.mock import MagicMock

pytest.importorskip("torch")

import bridge.server as server
from tests.dummy_ws import DummyWS


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
    """In PLAYER mode, a server-role peer shouldn't get a full policy loop. Config-only is fine."""
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "episode_state.json")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg("PLAYER")

    build_policy = MagicMock()
    monkeypatch.setattr(server, "build_policy_from_config", build_policy)
    # if workers somehow start anyway, keep them from doing real work
    monkeypatch.setattr(server, "MetricsLoop", lambda *a, **k: asyncio.sleep(999999))
    monkeypatch.setattr(server, "HeartBeatLoop", lambda *a, **k: asyncio.sleep(999999))
    monkeypatch.setattr(server, "PolicyWorker", lambda *a, **k: asyncio.sleep(999999))

    ws = DummyWS([_hello("server")], disconnect_after=True)
    await server.Handle(ws, cfg)

    assert not build_policy.called, "server-role in PLAYER must not start policy/workers"


@pytest.mark.asyncio
async def test_server_role_can_flip_to_server_bot_via_config_update(tmp_path, monkeypatch):
    """Connect as server in PLAYER mode, flip to SERVER_BOT via config_update, and workers should start."""
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
    """A client in PLAYER mode should get kicked when you switch to SERVER_BOT."""
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "episode_state.json")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg("PLAYER")

    monkeypatch.setattr(server, "build_policy_from_config", MagicMock(return_value=MagicMock(act=MagicMock(return_value={"payload": {}}))))
    monkeypatch.setattr(server, "MetricsLoop", lambda *a, **k: asyncio.sleep(999999))
    monkeypatch.setattr(server, "HeartBeatLoop", lambda *a, **k: asyncio.sleep(999999))
    monkeypatch.setattr(server, "PolicyWorker", lambda *a, **k: asyncio.sleep(999999))

    ws = DummyWS([_hello("client"), _config_update("SERVER_BOT")], disconnect_after=False)

    # Handle() should exit once it closes the wrong-role client
    try:
        await asyncio.wait_for(server.Handle(ws, cfg), timeout=1.0)
    except asyncio.TimeoutError:
        pytest.fail("server.Handle() did not terminate after closing wrong-role client on mode change")

    assert ws.closed, "ws must be closed when client becomes disallowed after mode switch"