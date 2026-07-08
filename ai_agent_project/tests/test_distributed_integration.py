# -----------------------------------------------------------------------------
# Distributed integration and runtime overlay tests
#
# These go a step beyond unit tests: they check that editing runtime_overrides.yaml
# on disk actually hot-reloads into the running bridge (same as the in-game UI).
# Two optional tests spawn a real bridge subprocess or packaged .exe for a live
# WebSocket handshake — skipped unless you opt in with env vars.
#
# Catches the painful prod issue where you change the overlay file but nothing
# updates until reconnect, invalid YAML taking down the bridge, or a packaged
# build failing to accept a hello on the wire.
#
# Optional: BRIDGE_SUBPROCESS_TEST=1, RUN_PACKAGED_BRIDGE=1 + built exe.
# -----------------------------------------------------------------------------
from __future__ import annotations

import asyncio
import json
import os
import pathlib
import subprocess
import sys
import time
from unittest.mock import MagicMock

import pytest
import yaml

FILE = pathlib.Path(__file__).resolve()
ROOT = FILE.parents[1]
sys.path.insert(0, str(ROOT))

pytest.importorskip("torch")

import bridge.server as server

from tests.test_connection_lifecycle import (  # noqa: E402
    DummyWS,
    _fake_cfg,
    _hello_client,
    _minimal_observation,
)


@pytest.mark.asyncio
async def test_runtime_overlay_file_triggers_hot_reload(tmp_path, monkeypatch):
    """
    Editing runtime_overrides.yaml on disk must reload overlay and call apply_runtime_config,
    matching GUI config_update behavior (fixes disconnect when only the file changes).
    """
    data = tmp_path / "data"
    data.mkdir(parents=True, exist_ok=True)
    overlay_path = data / "runtime_overrides.yaml"

    monkeypatch.setattr(server, "sharedDir", tmp_path / "shared")
    (tmp_path / "shared").mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(server, "dataDir", data)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", data / "episode_state.json")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", overlay_path)

    cfg = _fake_cfg(tmp_path)
    cfg.bridge["runtime_overlay_poll_interval_s"] = 0.08
    cfg.runtime["policy"] = cfg.runtime.get("policy") or {}
    cfg.runtime["policy"]["reward"] = {"step_penalty": -0.01, "survival_reward": 0.0}

    policy_instance = MagicMock()
    policy_instance.act = MagicMock(return_value={"payload": {}})
    policy_instance.apply_runtime_config = MagicMock()
    monkeypatch.setattr(server, "build_policy_from_config", lambda c: policy_instance)

    ws = DummyWS([_hello_client(), _minimal_observation()], block_after=True)
    handle_task = asyncio.create_task(server.Handle(ws, cfg))

    await asyncio.sleep(0.35)

    overlay = {
        "control_mode": "PLAYER",
        "policy": {"reward": {"step_penalty": -0.88, "max_steps_per_episode": 999}},
    }
    with open(overlay_path, "w", encoding="utf-8") as f:
        yaml.safe_dump(overlay, f, default_flow_style=False, allow_unicode=True, sort_keys=False)

    await asyncio.sleep(0.45)

    found = False
    for call in policy_instance.apply_runtime_config.call_args_list:
        rt = call[0][0] if call[0] else {}
        if not isinstance(rt, dict):
            continue
        sp = ((rt.get("policy") or {}).get("reward") or {}).get("step_penalty")
        if sp == -0.88:
            found = True
            break
    assert found, "apply_runtime_config should run after overlay file change with step_penalty -0.88"

    ws.close()
    await asyncio.wait_for(handle_task, timeout=15.0)


def _pick_free_port() -> int:
    import socket

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    _, port = s.getsockname()
    s.close()
    return int(port)


@pytest.mark.integration
@pytest.mark.skipif(not os.environ.get("BRIDGE_SUBPROCESS_TEST"), reason="Set BRIDGE_SUBPROCESS_TEST=1")
def test_subprocess_bridge_websocket_hello():
    """Real TCP stack: start bridge.server on ephemeral port, send client hello, expect JSON reply."""
    pytest.importorskip("websockets")
    import websockets

    port = _pick_free_port()
    env = os.environ.copy()
    env["AI_AGENT_BRIDGE_PORT"] = str(port)
    env["PYTHONPATH"] = str(ROOT) + os.pathsep + env.get("PYTHONPATH", "")

    popen_kw: dict = {
        "cwd": str(ROOT),
        "env": env,
        "stdout": subprocess.DEVNULL,
        "stderr": subprocess.DEVNULL,
    }
    if sys.platform == "win32":
        popen_kw["creationflags"] = subprocess.CREATE_NO_WINDOW  # type: ignore[attr-defined]
    proc = subprocess.Popen([sys.executable, "-m", "bridge.server"], **popen_kw)

    async def _probe():
        uri = f"ws://127.0.0.1:{port}"
        for _ in range(80):
            try:
                async with websockets.connect(uri, open_timeout=3) as ws:
                    await ws.send(
                        json.dumps(
                            {
                                "proto": "1",
                                "kind": "hello",
                                "role": "client",
                                "control_mode": "PLAYER",
                            }
                        )
                    )
                    raw = await asyncio.wait_for(ws.recv(), timeout=10.0)
                    msg = json.loads(raw)
                    assert msg.get("proto") == "1"
                    assert msg.get("kind") in ("episode_start", "bridge_health", "event")
                    return
            except (ConnectionError, OSError, asyncio.TimeoutError):
                await asyncio.sleep(0.15)
        raise AssertionError("bridge did not accept websocket in time")

    try:
        time.sleep(0.5)
        asyncio.run(_probe())
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()


def _default_packaged_exe() -> pathlib.Path:
    return ROOT / "packaging" / "dist" / "minecraft_ai_bridge" / "minecraft_ai_bridge.exe"


@pytest.mark.e2e
@pytest.mark.skipif(
    not os.environ.get("RUN_PACKAGED_BRIDGE_E2E"),
    reason="Set RUN_PACKAGED_BRIDGE_E2E=1 (optional E2E_BRIDGE_EXE=path to exe)",
)
def test_packaged_bridge_exe_websocket_hello(tmp_path):
    """Frozen bridge: same handshake as subprocess test using the packaged executable."""
    pytest.importorskip("websockets")
    import websockets

    raw_exe = os.environ.get("E2E_BRIDGE_EXE", "").strip()
    exe = pathlib.Path(raw_exe) if raw_exe else _default_packaged_exe()
    if not exe.is_file():
        pytest.skip(f"Packaged bridge not found: {exe}")

    port = _pick_free_port()
    data_dir = tmp_path / "BridgeData"
    data_dir.mkdir(parents=True, exist_ok=True)

    env = os.environ.copy()
    env["AI_AGENT_BRIDGE_PORT"] = str(port)
    env["AI_AGENT_BRIDGE_DATA"] = str(data_dir)

    popen_kw = {
        "cwd": str(exe.parent),
        "env": env,
        "stdout": subprocess.DEVNULL,
        "stderr": subprocess.DEVNULL,
    }
    if sys.platform == "win32":
        popen_kw["creationflags"] = subprocess.CREATE_NO_WINDOW  # type: ignore[attr-defined]
    proc = subprocess.Popen([str(exe)], **popen_kw)

    async def _probe():
        uri = f"ws://127.0.0.1:{port}"
        for _ in range(100):
            try:
                async with websockets.connect(uri, open_timeout=3) as ws:
                    await ws.send(
                        json.dumps(
                            {
                                "proto": "1",
                                "kind": "hello",
                                "role": "client",
                                "control_mode": "PLAYER",
                            }
                        )
                    )
                    raw = await asyncio.wait_for(ws.recv(), timeout=15.0)
                    msg = json.loads(raw)
                    assert msg.get("proto") == "1"
                    return
            except (ConnectionError, OSError, asyncio.TimeoutError):
                await asyncio.sleep(0.2)
        raise AssertionError("packaged bridge did not accept websocket")

    try:
        time.sleep(0.8)
        asyncio.run(_probe())
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()


@pytest.mark.asyncio
async def test_runtime_overlay_file_reload_skips_invalid_yaml(tmp_path, monkeypatch):
    """Corrupt YAML must not clear the in-memory overlay or crash the watch loop."""
    data = tmp_path / "data"
    data.mkdir(parents=True, exist_ok=True)
    overlay_path = data / "runtime_overrides.yaml"

    monkeypatch.setattr(server, "sharedDir", tmp_path / "shared")
    (tmp_path / "shared").mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(server, "dataDir", data)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", data / "episode_state.json")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", overlay_path)

    cfg = _fake_cfg(tmp_path)
    cfg.bridge["runtime_overlay_poll_interval_s"] = 0.08

    policy_instance = MagicMock()
    policy_instance.act = MagicMock(return_value={"payload": {}})
    policy_instance.apply_runtime_config = MagicMock()
    monkeypatch.setattr(server, "build_policy_from_config", lambda c: policy_instance)

    ws = DummyWS([_hello_client(), _minimal_observation()], block_after=True)
    handle_task = asyncio.create_task(server.Handle(ws, cfg))
    await asyncio.sleep(0.35)

    overlay_path.write_text("{ not yaml: [[", encoding="utf-8")
    await asyncio.sleep(0.35)

    ws.close()
    await asyncio.wait_for(handle_task, timeout=15.0)
    assert policy_instance.apply_runtime_config.called
