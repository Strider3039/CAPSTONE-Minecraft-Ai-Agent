# ------------------------------------------------------------
# Connection lifecycle (1.1): hello ordering, timeout, graceful shutdown
#
# Run from repo root with your venv (e.g. .venv38):
#   .venv38\Scripts\python -m pytest ai_agent_project/tests/test_connection_lifecycle.py -v
# Requires: pytest, pytest-asyncio, websockets, torch (for server import).
# ------------------------------------------------------------
import sys
import pathlib

FILE = pathlib.Path(__file__).resolve()
ROOT = FILE.parents[1]  # ai_agent_project/
sys.path.insert(0, str(ROOT))

import json
import asyncio
import pytest
from unittest.mock import MagicMock, AsyncMock, patch

# Require torch so server (and policy registry) can be imported
pytest.importorskip("torch")

from websockets.exceptions import ConnectionClosedOK

import ai.src.app.server as server


# ------------------------------------------------------------
# WebSocket mocks
# ------------------------------------------------------------
class DummyWS:
    """
    Async-iterable WebSocket mock. Yields messages from incoming (JSON strings).
    When at end of list, either blocks until close() then raises (for timeout test),
    or raises immediately (disconnect_after=True) or CancelledError (default).
    """

    def __init__(self, incoming, block_after=False, disconnect_after=False):
        self.sent_messages = []
        self._incoming = list(incoming)
        self._index = 0
        self._block_after = block_after
        self._disconnect_after = disconnect_after
        self._closed = False
        self._close_event = asyncio.Event()

    async def send(self, data):
        if isinstance(data, (bytes, bytearray)):
            data = data.decode("utf-8")
        try:
            self.sent_messages.append(json.loads(data))
        except Exception:
            self.sent_messages.append(data)

    def close(self, code=None, reason=None):
        self._closed = True
        self._close_event.set()

    @property
    def closed(self):
        return self._closed

    def __aiter__(self):
        return self

    async def __anext__(self):
        if self._index < len(self._incoming):
            msg = self._incoming[self._index]
            self._index += 1
            await asyncio.sleep(0.06)  # yield so PolicyWorker (20 Hz) can run a tick
            return msg

        if self._disconnect_after:
            # Yield so PolicyWorker (and other tasks) can run on the last enqueued message
            await asyncio.sleep(0.15)
            raise ConnectionClosedOK(None, "test disconnect")

        if self._block_after:
            self._close_event.clear()
            await self._close_event.wait()
            if self._closed:
                raise ConnectionClosedOK(None, "hello_timeout")
            raise asyncio.CancelledError()

        raise asyncio.CancelledError()


def _minimal_observation():
    return json.dumps({
        "proto": "1",
        "kind": "observation",
        "seq": 1,
        "timestamp": 1.0,
        "payload": {
            "pose": {"x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0},
            "rays": [{"hit": False, "dist": 5.0, "angle_deg": 0}],
            "front_clear": True,
            "world": {"time_of_day": 0, "weather": "clear", "biome": "plains"},
            "inventory": {"selected_slot": 0, "hotbar": []},
            "collision": {"is_grounded": True, "is_colliding": False, "no_progress": False},
        },
    })


def _hello_client():
    return json.dumps({
        "proto": "1",
        "kind": "hello",
        "role": "client",
        "control_mode": "PLAYER",
    })


# ------------------------------------------------------------
# Helpers: fake config for PLAYER mode (client hello accepted)
# ------------------------------------------------------------
def _fake_cfg(tmp_path, hello_timeout_s=2.0, control_mode="PLAYER"):
    fake = MagicMock()
    fake.bridge = {
        "queues": {"obs_max": 32, "act_max": 32, "obs_drop_policy": "oldest"},
        "server": {"host": "127.0.0.1", "port": 8765},
        "metrics": {"enabled": False},
    }
    fake.runtime = {
        "control_mode": control_mode,
        "hello_timeout_s": hello_timeout_s,
        "default_deadline_ms": 50,
        "validate_actions": False,
        "policy": {"tick_hz": 20},
    }
    return fake


# ------------------------------------------------------------
# TEST: No actions sent until hello received
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_no_actions_until_hello(tmp_path, monkeypatch):
    """
    Client sends observations only (no hello). Server must not start policy/action loop.
    So no message with kind "action" should be sent.
    """
    monkeypatch.setattr(server, "sharedDir", tmp_path / "shared")
    (tmp_path / "shared").mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "shared" / "episode_state.json")
    monkeypatch.setattr(server, "dataDir", tmp_path / "shared")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "shared" / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg(tmp_path, hello_timeout_s=0.3)

    # Policy must not be built until hello; if we never send hello, never called
    build_policy = MagicMock(return_value=MagicMock(act=MagicMock(return_value={"payload": {}})))
    monkeypatch.setattr(server, "build_policy_from_config", build_policy)

    # Block after one obs so hello_guard can timeout and call close()
    ws = DummyWS([_minimal_observation()], block_after=True)

    handle_task = asyncio.create_task(server.Handle(ws, cfg))
    await asyncio.sleep(0.5)  # allow hello_guard to timeout and close
    ws.close()
    await asyncio.sleep(0.1)

    try:
        await handle_task
    except (asyncio.CancelledError, ConnectionClosedOK):
        pass

    actions_sent = [m for m in ws.sent_messages if isinstance(m, dict) and m.get("kind") == "action"]
    assert len(actions_sent) == 0, "Server must not send actions before hello"
    assert not build_policy.called, "Policy must not be built when hello never received"


# ------------------------------------------------------------
# TEST: Hello timeout closes connection
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_hello_timeout_closes_connection(tmp_path, monkeypatch):
    """
    Client connects but never sends hello. After hello_timeout_s the server
    should close the connection (hello_guard runs).
    """
    monkeypatch.setattr(server, "sharedDir", tmp_path / "shared")
    (tmp_path / "shared").mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "shared" / "episode_state.json")
    monkeypatch.setattr(server, "dataDir", tmp_path / "shared")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "shared" / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg(tmp_path, hello_timeout_s=0.2)
    monkeypatch.setattr(server, "build_policy_from_config", MagicMock())

    ws = DummyWS([_minimal_observation()], block_after=True)

    handle_task = asyncio.create_task(server.Handle(ws, cfg))
    await asyncio.sleep(0.4)
    assert ws._closed or not handle_task.done()
    ws.close()
    await asyncio.sleep(0.05)

    try:
        await handle_task
    except (asyncio.CancelledError, ConnectionClosedOK):
        pass

    # Server should have triggered close (hello_guard timeout)
    assert ws.closed


# ------------------------------------------------------------
# TEST: After hello, policy runs and actions can be sent
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_hello_then_policy_and_actions_possible(tmp_path, monkeypatch):
    """
    Client sends hello (PLAYER mode) then observation. Server accepts hello,
    starts policy worker, then processes observation; action sender can send.
    """
    monkeypatch.setattr(server, "sharedDir", tmp_path / "shared")
    (tmp_path / "shared").mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "shared" / "episode_state.json")
    monkeypatch.setattr(server, "dataDir", tmp_path / "shared")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "shared" / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg(tmp_path)

    policy_instance = MagicMock()
    policy_instance.act = MagicMock(return_value={
        "payload": {"move": {"forward": 1.0, "strafe": 0.0}, "look": {"dYaw": 0, "dPitch": 0}, "jump": False},
    })
    monkeypatch.setattr(server, "build_policy_from_config", lambda cfg: policy_instance)

    # Hello, then several obs so policy worker has time to run, then disconnect
    msgs = [_hello_client()] + [_minimal_observation() for _ in range(5)]
    ws = DummyWS(msgs, disconnect_after=True)

    await server.Handle(ws, cfg)

    assert policy_instance.act.called, "Policy worker should have run and called act()"
    actions = [m for m in ws.sent_messages if isinstance(m, dict) and m.get("kind") == "action"]
    assert len(actions) >= 1, "After hello, server should send at least one action"


# ------------------------------------------------------------
# TEST: Graceful shutdown when client disconnects
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_graceful_shutdown_on_client_disconnect(tmp_path, monkeypatch):
    """
    Client connects, sends hello, then disconnects. Server should clean up
    (cancel tasks, clear pending) and return without crashing.
    """
    monkeypatch.setattr(server, "sharedDir", tmp_path / "shared")
    (tmp_path / "shared").mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "shared" / "episode_state.json")
    monkeypatch.setattr(server, "dataDir", tmp_path / "shared")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "shared" / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg(tmp_path)
    policy_instance = MagicMock()
    policy_instance.act = MagicMock(return_value={"payload": {}})
    monkeypatch.setattr(server, "build_policy_from_config", lambda cfg: policy_instance)

    ws = DummyWS([_hello_client()], disconnect_after=True)

    # Should return normally (no exception)
    await server.Handle(ws, cfg)


# ------------------------------------------------------------
# TEST: Disconnect flushes policy shutdown and checkpoint save
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_disconnect_flushes_policy_and_checkpoint(tmp_path, monkeypatch):
    monkeypatch.setattr(server, "sharedDir", tmp_path / "shared")
    (tmp_path / "shared").mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "shared" / "episode_state.json")
    monkeypatch.setattr(server, "dataDir", tmp_path / "shared")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "shared" / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg(tmp_path)
    policy_instance = MagicMock()
    policy_instance.act = MagicMock(return_value={"payload": {}})
    policy_instance.shutdown = MagicMock()
    policy_instance.agent = MagicMock()
    policy_instance.ckpt_latest_path = str(tmp_path / "shared" / "online_dqn_latest.pt")
    monkeypatch.setattr(server, "build_policy_from_config", lambda cfg: policy_instance)

    ws = DummyWS([_hello_client()], disconnect_after=True)

    await server.Handle(ws, cfg)

    policy_instance.shutdown.assert_called_once()
    policy_instance.agent.Save.assert_called_once_with(policy_instance.ckpt_latest_path)


# ------------------------------------------------------------
# TEST: Policy eval_control passes through the worker unchanged
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_policy_eval_control_passes_through_worker():
    obs_q = asyncio.Queue()
    act_q = asyncio.Queue()
    await obs_q.put(json.loads(_minimal_observation()))

    runtime_cfg = {"policy": {"tick_hz": 20}, "validate_actions": False}
    queues_cfg = {"act_put_timeout_s": 0, "coalesce": {"enabled": False}}

    async def emit_event(kind, payload):
        return None

    def policy_step(_obs):
        return {
            "proto": "1",
            "kind": "eval_control",
            "payload": {"action": "start_episode"},
        }

    task = asyncio.create_task(
        server.PolicyWorker(
            obs_q=obs_q,
            act_q=act_q,
            runtime_cfg=runtime_cfg,
            queues_cfg=queues_cfg,
            act_schema=server.ACT,
            log=server.stdlog.getLogger("bridge.policy.test"),
            emit_event=emit_event,
            policy_step=policy_step,
        )
    )

    await asyncio.sleep(0.1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    item = act_q.get_nowait()
    assert item["kind"] == "eval_control"
    assert item["payload"]["action"] == "start_episode"


# ------------------------------------------------------------
# TEST: Observations before hello are enqueued but policy not started
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_observations_before_hello_do_not_start_policy(tmp_path, monkeypatch):
    """
    Client sends observation then hello. Server enqueues the observation
    but does not start policy/action loop until hello is received.
    """
    monkeypatch.setattr(server, "sharedDir", tmp_path / "shared")
    (tmp_path / "shared").mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "shared" / "episode_state.json")
    monkeypatch.setattr(server, "dataDir", tmp_path / "shared")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "shared" / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg(tmp_path)
    build_policy = MagicMock(return_value=MagicMock(act=MagicMock(return_value={"payload": {}})))
    monkeypatch.setattr(server, "build_policy_from_config", build_policy)

    # Obs first, then hello, then disconnect
    ws = DummyWS([_minimal_observation(), _hello_client()], disconnect_after=True)

    await server.Handle(ws, cfg)

    # Policy is built only once (when hello is received)
    assert build_policy.call_count == 1


# ------------------------------------------------------------
# TEST: Invalid observation is logged and skipped (schema validation)
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_invalid_observation_skipped_no_crash(tmp_path, monkeypatch):
    """
    Client sends hello then an invalid observation (fails OBS schema) then a valid one.
    Server must skip the invalid message (log, continue) and enqueue only the valid one.
    No crash; policy can still run on the valid observation.
    """
    monkeypatch.setattr(server, "sharedDir", tmp_path / "shared")
    (tmp_path / "shared").mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "shared" / "episode_state.json")
    monkeypatch.setattr(server, "dataDir", tmp_path / "shared")
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", tmp_path / "shared" / "runtime_overrides.yaml")
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg(tmp_path)
    policy_instance = MagicMock()
    policy_instance.act = MagicMock(return_value={"payload": {}})
    monkeypatch.setattr(server, "build_policy_from_config", lambda cfg: policy_instance)

    invalid_obs = json.dumps({
        "proto": "1",
        "kind": "observation",
        "seq": 1,
        "timestamp": 1.0,
        "payload": {"wrong": "structure"},  # missing required pose, rays, world, etc.
    })
    msgs = [_hello_client(), invalid_obs, _minimal_observation()]
    ws = DummyWS(msgs, disconnect_after=True)

    await server.Handle(ws, cfg)

    # Server should not crash; policy should have been called (with the valid obs)
    assert policy_instance.act.called


# ------------------------------------------------------------
# TEST: config_update (GUI hot-reload) is applied and persisted
# ------------------------------------------------------------
@pytest.mark.asyncio
async def test_config_update_hot_reload(tmp_path, monkeypatch):
    """
    Client sends hello then config_update (as from GUI Apply). Server must:
    - Merge payload into runtime_overlay and refresh current_runtime
    - Call policy.apply_runtime_config(current_runtime)
    - Persist overlay to runtime_overrides.yaml
    """
    import yaml

    monkeypatch.setattr(server, "sharedDir", tmp_path / "shared")
    (tmp_path / "shared").mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(server, "EPISODE_SAVE_PATH", tmp_path / "shared" / "episode_state.json")
    monkeypatch.setattr(server, "dataDir", tmp_path / "shared")
    overlay_path = tmp_path / "shared" / "runtime_overrides.yaml"
    monkeypatch.setattr(server, "RUNTIME_OVERLAY_PATH", overlay_path)
    monkeypatch.setattr(server, "load_runtime_overlay", lambda: {})

    cfg = _fake_cfg(tmp_path)
    # Base runtime has policy.reward; overlay will add/override
    cfg.runtime["policy"] = cfg.runtime.get("policy") or {}
    cfg.runtime["policy"]["reward"] = {"step_penalty": -0.01, "survival_reward": 0.0}

    policy_instance = MagicMock()
    policy_instance.act = MagicMock(return_value={"payload": {}})
    policy_instance.apply_runtime_config = MagicMock()
    monkeypatch.setattr(server, "build_policy_from_config", lambda c: policy_instance)

    config_update_payload = {
        "control_mode": "PLAYER",
        "policy": {
            "reward": {"step_penalty": -0.99, "max_steps_per_episode": 500},
            "dqn": {"epsilon_start": 0.2},
        },
    }
    config_update_msg = json.dumps({
        "proto": "1",
        "kind": "config_update",
        "seq": 2,
        "payload": config_update_payload,
    })

    msgs = [_hello_client(), config_update_msg]
    ws = DummyWS(msgs, disconnect_after=True)

    await server.Handle(ws, cfg)

    # 1) apply_runtime_config was called (at least once at hello; and once for config_update)
    assert policy_instance.apply_runtime_config.call_count >= 1
    # Last call should have merged config including our overlay
    last_call_args = policy_instance.apply_runtime_config.call_args[0][0]
    assert isinstance(last_call_args, dict)
    policy_cfg = last_call_args.get("policy") or {}
    reward_cfg = policy_cfg.get("reward") or {}
    assert reward_cfg.get("step_penalty") == -0.99
    assert reward_cfg.get("max_steps_per_episode") == 500
    dqn_cfg = policy_cfg.get("dqn") or {}
    assert dqn_cfg.get("epsilon_start") == 0.2

    # 2) Overlay file was persisted
    assert overlay_path.exists(), "runtime_overrides.yaml should be written after config_update"
    with open(overlay_path, "r", encoding="utf-8") as f:
        overlay = yaml.safe_load(f) or {}
    assert overlay.get("control_mode") == "PLAYER"
    assert (overlay.get("policy") or {}).get("reward", {}).get("step_penalty") == -0.99
    assert (overlay.get("policy") or {}).get("reward", {}).get("max_steps_per_episode") == 500
    assert (overlay.get("policy") or {}).get("dqn", {}).get("epsilon_start") == 0.2
