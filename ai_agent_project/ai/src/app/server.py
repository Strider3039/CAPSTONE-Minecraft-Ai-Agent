import asyncio
import os
import sys
import time
import pathlib
import logging as stdlog
import contextlib
import traceback
from typing import Any, Dict, Optional
import uuid

from websockets.server import serve, WebSocketServerProtocol
from websockets.exceptions import (
    ConnectionClosed,
    ConnectionClosedOK,
    ConnectionClosedError,
)
from jsonschema import validate, ValidationError

# from ai/src/app to ai
sys.path.append(str(pathlib.Path(__file__).resolve().parents[3]))
from ai.src.utils.config import LoadConfig, DeepMerge, LoadYaml, SaveYaml
from ai.src.utils.logging import SetupLogging, WriteMetric

from ai.src.policy.registry import build_policy_from_config
from ai.src.app.policy_worker import PolicyWorker


# ---------- Fast JSON ----------
try:
    import orjson

    def _dumps(obj) -> str:
        return orjson.dumps(obj).decode("utf-8")  # TEXT

    def _loads(s):
        if isinstance(s, (bytes, bytearray)):
            return orjson.loads(s)
        return orjson.loads(s.encode("utf-8"))

except Exception:
    import json as _json

    def _dumps(obj) -> str:
        return _json.dumps(obj, ensure_ascii=False, separators=(",", ":"))

    def _loads(s):
        return _json.loads(s if isinstance(s, str) else s.decode("utf-8"))


# ---------- Paths ----------
server_root = pathlib.Path(__file__).resolve()
project_root = server_root.parents[3]  # ai_agent_project

sharedDir = project_root / "shared"
schemasDir = sharedDir / "schemas"

dataDir = sharedDir / "Data"
dataDir.mkdir(parents=True, exist_ok=True)

OBS = (schemasDir / "observation.schema.json").read_text("utf-8")
ACT = (schemasDir / "action.schema.json").read_text("utf-8")
EVT = (schemasDir / "event.schema.json").read_text("utf-8")

import json as _json  # local for schema loads only
OBS = _json.loads(OBS)
ACT = _json.loads(ACT)
EVT = _json.loads(EVT)

# ---------- Bridge role/control_mode (must match Forge: ForgeWebSocketClient, ServerBridgeWebSocketClient) ----------
ROLE_CLIENT = "client"
ROLE_SERVER = "server"
MODE_PLAYER = "PLAYER"
MODE_SERVER_BOT = "SERVER_BOT"
# PLAYER: client or server (integrated SP = client only). SERVER_BOT: server only (dedicated server connects; client does not).
ROLES_BY_MODE = {MODE_PLAYER: (ROLE_CLIENT, ROLE_SERVER), MODE_SERVER_BOT: (ROLE_SERVER,)}

# ---------- Episode persistence ----------
EPISODE_SAVE_PATH = dataDir / "episode_state.json"
episode = 0

# ---------- Runtime overlay persistence (hot-reload values: control_mode, policy.reward, policy.dqn) ----------
RUNTIME_OVERLAY_PATH = dataDir / "runtime_overrides.yaml"

# ---------- Metrics sink path (resolved at startup relative to dataDir so logs go to a known location) ----------
RESOLVED_METRICS_SINK_PATH: Optional[pathlib.Path] = None
connection_count = 0


def resolve_metrics_sink_path(cfg: Any, base_dir: pathlib.Path) -> pathlib.Path:
    """Resolve metrics.sink.path relative to base_dir (e.g. shared/Data) so the file is always in a known place."""
    try:
        sink = (cfg.bridge or {}).get("metrics") or {}
        sink = sink.get("sink") if isinstance(sink.get("sink"), dict) else {}
        path_str = sink.get("path")
    except Exception:
        path_str = None
    if not path_str:
        return base_dir / "logs" / "bridge_metrics.ndjson"
    path = pathlib.Path(path_str)
    if path.is_absolute():
        return path
    return (base_dir / path).resolve()


def load_runtime_overlay() -> Dict[str, Any]:
    """Load persisted runtime overlay from disk. Returns empty dict if missing or invalid."""
    try:
        raw = LoadYaml(RUNTIME_OVERLAY_PATH)
        return dict(raw) if isinstance(raw, dict) else {}
    except Exception as e:
        stdlog.getLogger("bridge.server").warning(
            "failed to load runtime overlay",
            extra={"path": str(RUNTIME_OVERLAY_PATH), "error": str(e)},
        )
        return {}


def save_runtime_overlay(overlay: Dict[str, Any]) -> None:
    """Persist runtime overlay to disk so next bridge start uses the same values."""
    if not isinstance(overlay, dict):
        return
    try:
        SaveYaml(RUNTIME_OVERLAY_PATH, overlay)
        stdlog.getLogger("bridge.server").debug(
            "saved runtime overlay",
            extra={"path": str(RUNTIME_OVERLAY_PATH), "keys": list(overlay.keys())},
        )
    except Exception as e:
        stdlog.getLogger("bridge.server").warning(
            "failed to save runtime overlay",
            extra={"path": str(RUNTIME_OVERLAY_PATH), "error": str(e)},
        )


# ---------- Episode counter (continued) ----------
episode_start_time: Optional[float] = None
MC_DAY_SECONDS = 1200  # 20 min


def LoadEpisodeNumber() -> None:
    global episode
    log = stdlog.getLogger("bridge.server")
    try:
        if EPISODE_SAVE_PATH.exists():
            data = _json.loads(EPISODE_SAVE_PATH.read_text("utf-8"))
            episode = int(data.get("episode", 0))
            log.info("Loaded episode", extra={"episode": episode})
    except Exception as e:
        log.warning("Failed to load episode persistence", extra={"error": str(e)})


def SaveEpisodeNumber() -> None:
    log = stdlog.getLogger("bridge.server")
    try:
        EPISODE_SAVE_PATH.write_text(_json.dumps({"episode": episode}))
    except Exception as e:
        log.warning("Failed to save episode number", extra={"error": str(e)})


async def SendEvents(ws: WebSocketServerProtocol, kind: str, payload: dict) -> None:
    log = stdlog.getLogger("bridge.server.SendEvents")
    msg = {
        "proto": "1",
        "kind": kind,
        "seq": 0,
        "timestamp": time.time(),
        "payload": {kind: payload},
    }
    try:
        validate(instance=msg, schema=EVT)
        log.debug("event validated", extra={"kind": kind, "seq": msg.get("seq")})
    except ValidationError as e:
        log.warning("internal event failed schema", extra={"error": str(e), "kind": kind})

    await ws.send(_dumps(msg))


async def EnqueueObservation(
    q: asyncio.Queue, item: dict, state: dict, drop_policy: str
) -> None:
    drop_policy = (drop_policy or "oldest").lower()

    if drop_policy == "block":
        await q.put(item)
    else:
        try:
            q.put_nowait(item)
        except asyncio.QueueFull:
            if drop_policy == "oldest":
                try:
                    _ = q.get_nowait()
                    state["obsDropped"] = state.get("obsDropped", 0) + 1
                    q.put_nowait(item)
                except Exception:
                    state["obsDropped"] = state.get("obsDropped", 0) + 1
            elif drop_policy == "reject":
                state["obsDropped"] = state.get("obsDropped", 0) + 1

    state["obsHighWatermark"] = max(state.get("obsHighWatermark", 0), q.qsize())
    state["total_obs_received"] = state.get("total_obs_received", 0) + 1


async def start_new_episode(ws, obs_q, obs_state, obs_drop_policy: str) -> None:
    """
    Increment episode counter, reset timer, and emit episode_start.

    NOTE: event.schema.json only allows a 'reason' string for episode_start,
    so we encode the episode number inside the reason string.
    The client will perform the actual world reset when it receives episode_start.

    Also enqueue a synthetic episode_start event into obs_q so
    PolicyWorker/DQN can observe episode boundaries.
    """
    global episode, episode_start_time
    episode += 1
    episode_start_time = time.time()
    SaveEpisodeNumber()

    log = stdlog.getLogger("bridge.server")
    log.info("=== Starting Episode ===", extra={"episode": episode})

    reason_str = f"episode_{episode}_start"

    # Schema-safe payload for the client
    await SendEvents(ws, "episode_start", {"reason": reason_str})

    # Also notify the policy side
    if obs_q is not None and obs_state is not None:
        synthetic_evt = {
            "proto": "1",
            "kind": "episode_start",
            "seq": 0,
            "timestamp": episode_start_time,
            "payload": {"episode_start": {"reason": reason_str}},
        }
        await EnqueueObservation(obs_q, synthetic_evt, obs_state, obs_drop_policy)


async def MetricsLoop(
    stopEvt: asyncio.Event,
    cfg,
    obsState: dict,
    obsQ: asyncio.Queue,
    actState: dict,
    actQ: asyncio.Queue,
) -> None:
    log = stdlog.getLogger("bridge.server.MetricsLoop")
    metricsCfg = cfg.bridge.get("metrics", {})
    if not metricsCfg.get("enabled", True):
        return

    sink = metricsCfg.get("sink", {}) if isinstance(metricsCfg.get("sink", {}), dict) else {}
    if sink.get("kind", "file") != "file":
        log.debug("metrics sink disabled (non-file)", extra={"kind": sink.get("kind")})
        return

    sinkPath = RESOLVED_METRICS_SINK_PATH if RESOLVED_METRICS_SINK_PATH is not None else sink.get("path")
    if not sinkPath:
        return
    interval = float(metricsCfg.get("sample_interval_s", 2) or 2)

    while not stopEvt.is_set():
        try:
            now = time.time()
            last_ts = actState.get("_last_throughput_ts", now)
            dt = max(0.001, now - last_ts)
            obs_total = obsState.get("total_obs_received", 0)
            act_total = actState.get("total_acts_sent", 0)
            last_obs = actState.get("_last_obs_count", 0)
            last_act = actState.get("_last_act_count", 0)
            obs_per_sec = (obs_total - last_obs) / dt if dt >= 0.5 else None
            acts_per_sec = (act_total - last_act) / dt if dt >= 0.5 else None
            actState["_last_throughput_ts"] = now
            actState["_last_obs_count"] = obs_total
            actState["_last_act_count"] = act_total

            row = {
                "queue_obs_size": obsQ.qsize(),
                "queue_obs_high_watermark": obsState.get("obsHighWatermark", 0),
                "obs_dropped": obsState.get("obsDropped", 0),
                "queue_act_size": actQ.qsize(),
                "queue_act_high_watermark": actState.get("actHighWatermark", 0),
                "action_timeouts": actState.get("actionTimeouts", 0),
            }
            if obs_per_sec is not None:
                row["obs_per_sec"] = round(obs_per_sec, 1)
            if acts_per_sec is not None:
                row["acts_per_sec"] = round(acts_per_sec, 1)
            if "last_latency_p50_ms" in actState:
                row["tick_latency_p50_ms"] = actState["last_latency_p50_ms"]
            if "last_latency_p90_ms" in actState:
                row["tick_latency_p90_ms"] = actState["last_latency_p90_ms"]
            if "last_latency_hz" in actState:
                row["tick_latency_hz"] = actState["last_latency_hz"]

            WriteMetric(sinkPath, row)
        except Exception as e:
            log.warning("metrics write failed", extra={"error": str(e)})
        await asyncio.sleep(interval)


async def HeartBeatLoop(ws: WebSocketServerProtocol, stopEvt: asyncio.Event) -> None:
    try:
        await SendEvents(ws, "bridge_health", {"level": "info", "detail": "connected"})
        while not stopEvt.is_set():
            await asyncio.sleep(2.0)
            await SendEvents(ws, "bridge_health", {"level": "info", "detail": "alive"})
    except (asyncio.CancelledError, ConnectionClosed, ConnectionClosedOK, ConnectionClosedError):
        pass
    except Exception as e:
        stdlog.getLogger("bridge.server.Heartbeat").warning(
            "heartbeat loop error", extra={"error": str(e)}
        )


# Main connection handler
async def Handle(ws: WebSocketServerProtocol, cfg) -> None:
    global connection_count
    log = stdlog.getLogger("bridge.server")

    connection_count += 1
    if RESOLVED_METRICS_SINK_PATH and cfg.bridge.get("metrics", {}).get("enabled", True):
        try:
            WriteMetric(
                RESOLVED_METRICS_SINK_PATH,
                {"type": "connection", "connection_count": connection_count, "event": "connected"},
            )
        except Exception as e:
            log.debug("metrics connection event write failed", extra={"error": str(e)})

    policy = None

    ws_role = None
    ws_ready = asyncio.Event()

    runtime_cfg = getattr(cfg, "runtime", {}) or {}
    if not isinstance(runtime_cfg, dict):
        raise TypeError(f"cfg.runtime must be dict, got {type(runtime_cfg)}")

    # Mutable overlay for hot-reload; merged with runtime_cfg for "current" config.
    # Load persisted overlay from disk so last Apply (e.g. control_mode) is restored.
    runtime_overlay: Dict[str, Any] = dict(load_runtime_overlay())
    current_runtime: Dict[str, Any] = {}

    def refresh_current_runtime() -> None:
        current_runtime.clear()
        current_runtime.update(DeepMerge(dict(runtime_cfg), dict(runtime_overlay)))

    refresh_current_runtime()
    hello_timeout_s = float(current_runtime.get("hello_timeout_s", 2.0))

    stopEvt = asyncio.Event()
    tasks: list[asyncio.Task] = []
    started = False

    async def hello_guard():
        try:
            await asyncio.wait_for(ws_ready.wait(), timeout=hello_timeout_s)
        except asyncio.TimeoutError:
            log.warning("hello timeout", extra={"ws_id": id(ws)})
            with contextlib.suppress(Exception):
                await ws.close(code=1002, reason="hello_timeout")
            stopEvt.set()

    tasks.append(asyncio.create_task(hello_guard()))

    log.info(
        "ws connected",
        extra={"ws_id": id(ws), "remote": getattr(ws, "remote_address", None)},
    )

    queuesCfg = cfg.bridge.get("queues", {})
    obsState = {"obsHighWatermark": 0, "obsDropped": 0, "total_obs_received": 0}
    obsQueue: asyncio.Queue[dict] = asyncio.Queue(maxsize=int(queuesCfg.get("obs_max", 128)))
    obs_drop_policy = queuesCfg.get("obs_drop_policy", "oldest")

    actQueue: asyncio.Queue[dict] = asyncio.Queue(maxsize=int(queuesCfg.get("act_max", 64)))
    actState = {
        "actHighWatermark": 0,
        "actionTimeouts": 0,
        "total_acts_sent": 0,
        "_last_throughput_ts": time.time(),
        "_last_obs_count": 0,
        "_last_act_count": 0,
    }

    pending: dict[int, asyncio.Future] = {}

    async def emit_event(kind: str, payload: dict) -> None:
        await SendEvents(ws, kind, payload)

    seqCounter = 0

    async def SendAction(actionMsg: dict, timeoutMs: int = 300, wait_for_result: bool = False):
        nonlocal seqCounter

        if stopEvt.is_set():
            raise ConnectionError("bridge stopping")
        if ws.closed:
            raise ConnectionError("ws closed")
        if not ws_ready.is_set():
            raise ConnectionError("ws not ready (no hello)")

        # Ensure deadline_ms exists and is int-like
        deadline_ms = int(actionMsg.get("deadline_ms", 50) or 50)
        timeoutMs = max(int(timeoutMs), deadline_ms + 1000)

        seqCounter += 1

        # ---- normalize envelope
        actionMsg["seq"] = seqCounter
        actionMsg.setdefault("proto", "1")
        actionMsg.setdefault("kind", "action")
        actionMsg.setdefault("timestamp", time.time())

        payload = actionMsg.get("payload", {})
        if not isinstance(payload, dict):
            payload = {}
            actionMsg["payload"] = payload

        is_control = bool(payload.get("move")) or bool(payload.get("look"))

        # Extend control deadline based on YAML hold ticks (smoothing)
        if is_control:
            hold_ticks = int(current_runtime.get("continuous_hold_ticks", 3))
            hold_ms = max(1, hold_ticks) * 50
            actionMsg["deadline_ms"] = max(int(actionMsg.get("deadline_ms", 0) or 0), hold_ms)

        # await_result override from actionMsg
        if "await_result" in actionMsg:
            wait_for_result = bool(actionMsg["await_result"])

        # control should not block the pipeline
        if is_control:
            wait_for_result = False

        # REQUIRED: action_id must be TOP-LEVEL
        if not actionMsg.get("action_id"):
            actionMsg["action_id"] = f"a{seqCounter}_{uuid.uuid4().hex[:8]}"

        # Ensure payload exists
        if "payload" not in actionMsg or not isinstance(actionMsg["payload"], dict):
            actionMsg["payload"] = {}

        seq = int(actionMsg["seq"])

        if actionMsg.get("payload", {}).get("move") or actionMsg.get("payload", {}).get("look"):
            log.debug(
                "TX CONTROL",
                extra={"ws_id": id(ws), "seq": seq, "payload_keys": list(actionMsg["payload"].keys())},
            )
        else:
            log.debug(
                "TX ACTION",
                extra={
                    "ws_id": id(ws),
                    "seq": seq,
                    "action_id": actionMsg.get("action_id"),
                    "payload_keys": list(actionMsg.get("payload", {}).keys()),
                },
            )

        if not wait_for_result:
            await ws.send(_dumps(actionMsg))
            actState["total_acts_sent"] = actState.get("total_acts_sent", 0) + 1
            return None

        fut = asyncio.get_running_loop().create_future()
        pending[seq] = fut

        # SEND BEFORE WAITING
        await ws.send(_dumps(actionMsg))
        actState["total_acts_sent"] = actState.get("total_acts_sent", 0) + 1

        try:
            return await asyncio.wait_for(fut, timeout=timeoutMs / 1000.0)
        except asyncio.TimeoutError:
            actState["actionTimeouts"] += 1
            pending.pop(seq, None)
            raise
        except Exception:
            pending.pop(seq, None)
            raise

    def _exact_scheduler(hz: int):
        dt = 1.0 / float(max(1, hz))
        next_deadline = time.perf_counter()

        async def sleep_exact():
            nonlocal next_deadline
            next_deadline += dt
            delay = next_deadline - time.perf_counter()
            if delay > 0:
                await asyncio.sleep(delay)
            else:
                next_deadline = time.perf_counter()

        return sleep_exact

    async def ActionSenderLoop(stopEvt: asyncio.Event) -> None:
        resend_s = float(current_runtime.get("continuous_resend_interval_s", 0.05))
        hz = int(round(1.0 / max(0.01, resend_s)))
        sleep_exact = _exact_scheduler(hz)

        while not stopEvt.is_set():
            maxPerTick = int(current_runtime.get("policy", {}).get("max_actions_per_tick", 2))
            sent = 0

            while sent < maxPerTick:
                try:
                    item = actQueue.get_nowait()
                    if isinstance(item, dict):
                        log.debug(
                            "sender_got",
                            extra={
                                "await_result": bool(item.get("await_result", False)),
                                "action_id": item.get("action_id"),
                                "payload_keys": list((item.get("payload") or {}).keys()),
                            },
                        )
                    else:
                        log.debug("sender_got", extra={"type": type(item).__name__})
                except asyncio.QueueEmpty:
                    break

                # Coalesce ONLY non-ACK dicts: keep newest non-ACK; preserve ACK dicts by putting them back.
                # Every get_nowait() MUST be matched by task_done() for that item.
                if isinstance(item, dict) and not bool(item.get("await_result", False)):
                    latest = item
                    requeue: list[dict] = []

                    while True:
                        try:
                            peek = actQueue.get_nowait()
                        except asyncio.QueueEmpty:
                            break

                        if isinstance(peek, dict) and not bool(peek.get("await_result", False)):
                            # drop older non-ACK (account for it)
                            actQueue.task_done()
                            latest = peek
                            continue

                        # preserve ACK dicts (and stop coalescing)
                        if isinstance(peek, dict):
                            requeue.append(peek)
                            actQueue.task_done()
                        else:
                            # unknown item: preserve and stop
                            await actQueue.put(peek)
                            actQueue.task_done()
                        break

                    for a in requeue:
                        await actQueue.put(a)

                    item = latest

                if not isinstance(item, dict):
                    # Unknown item type; drop it (but do not crash)
                    sent += 1
                    # Mark done for the original get_nowait()
                    actQueue.task_done()
                    continue

                await_result = bool(item.get("await_result", False))

                default_disc_ms = int(float(current_runtime.get("discrete_action_timeout_s", 2.0)) * 1000)
                timeout_ms = int(item.get("timeout_ms", default_disc_ms if await_result else 0))

                # If ACK action is pending, don't create more in-flight; avoid head-of-line deadlocks.
                if await_result and len(pending) >= 1:
                    # Put it back and stop this tick
                    await actQueue.put(item)
                    actQueue.task_done()
                    break

                try:
                    await SendAction(item, timeoutMs=timeout_ms, wait_for_result=await_result)
                except Exception as e:
                    payload = item.get("payload") or {}
                    log.warning(
                        "action send failed",
                        extra={
                            "error": repr(e),
                            "seq": item.get("seq"),
                            "action_id": item.get("action_id"),
                            "await_result": await_result,
                            "timeout_ms": timeout_ms,
                            "payload_keys": list(payload.keys()),
                            "trace": traceback.format_exc(),
                        },
                    )
                finally:
                    actQueue.task_done()

                sent += 1

            await sleep_exact()

    # Receive loop
    try:
        async for raw in ws:
            try:
                msg = _loads(raw)
            except Exception:
                await SendEvents(ws, "bridge_health", {"level": "warn", "detail": "invalid_json"})
                continue

            if not isinstance(msg, dict) or msg.get("proto") != "1":
                continue

            kind = msg.get("kind")
            seq_in = msg.get("seq")
            log.debug("rx msg", extra={"ws_id": id(ws), "kind": kind, "seq": seq_in})

            if kind == "hello":
                ws_role = msg.get("role")
                log.info("ws hello", extra={"ws_id": id(ws), "role": ws_role})

                # If client or server sends control_mode in hello, apply overlay (must match Forge bridge_constants)
                if ws_role in (ROLE_CLIENT, ROLE_SERVER) and "control_mode" in msg:
                    client_mode = str(msg.get("control_mode", "")).strip()
                    if client_mode:
                        overlay_update = {"control_mode": client_mode}
                        new_overlay = DeepMerge(dict(runtime_overlay), overlay_update)
                        runtime_overlay.clear()
                        runtime_overlay.update(new_overlay)
                        refresh_current_runtime()
                        save_runtime_overlay(runtime_overlay)
                        log.info(
                            "control_mode from client hello",
                            extra={"ws_id": id(ws), "control_mode": client_mode},
                        )

                control_mode_raw = str(current_runtime.get("control_mode", MODE_SERVER_BOT)).strip()
                control_mode = control_mode_raw.replace("-", "_").upper()

                allowed_roles = ROLES_BY_MODE.get(control_mode, ())
                if control_mode not in (MODE_PLAYER, MODE_SERVER_BOT):
                    allowed_roles = (ROLE_CLIENT, ROLE_SERVER)
                if allowed_roles and ws_role not in allowed_roles:
                    log.warning(
                        "rejecting ws: control_mode=%s expects role in %s, got role=%s",
                        control_mode,
                        allowed_roles,
                        ws_role,
                        extra={"ws_id": id(ws), "role": ws_role, "control_mode": control_mode},
                    )
                    await ws.close(code=1008, reason="wrong_role")
                    return

                if not started:
                    started = True

                    # Load policy ONLY for accepted server ws (use current_runtime so overlay is applied)
                    policy = build_policy_from_config(current_runtime)
                    if hasattr(policy, "apply_runtime_config"):
                        policy.apply_runtime_config(current_runtime)

                    log.info(
                        "policy_loaded",
                        extra={
                            "ws_id": id(ws),
                            "policy_type": type(policy).__name__,
                            "has_act": hasattr(policy, "act"),
                            "has_step": hasattr(policy, "step"),
                            "callable": callable(policy),
                        },
                    )

                    async def emit_event(kind: str, payload: dict) -> None:
                        await SendEvents(ws, kind, payload)

                    # Start background tasks AFTER hello(role=server)
                    tasks.append(asyncio.create_task(MetricsLoop(stopEvt, cfg, obsState, obsQueue, actState, actQueue)))
                    tasks.append(asyncio.create_task(HeartBeatLoop(ws, stopEvt)))
                    tasks.append(asyncio.create_task(ActionSenderLoop(stopEvt)))

                    def on_latency_stats(p50_ms: float, p90_ms: float, hz: float) -> None:
                        actState["last_latency_p50_ms"] = p50_ms
                        actState["last_latency_p90_ms"] = p90_ms
                        actState["last_latency_hz"] = hz
                        if RESOLVED_METRICS_SINK_PATH is not None:
                            try:
                                WriteMetric(
                                    RESOLVED_METRICS_SINK_PATH,
                                    {"type": "tick_latency", "p50_ms": p50_ms, "p90_ms": p90_ms, "hz": hz},
                                )
                            except Exception:
                                pass

                    # RL worker (policy is guaranteed non-None here)
                    tasks.append(
                        asyncio.create_task(
                            PolicyWorker(
                                obs_q=obsQueue,
                                act_q=actQueue,
                                runtime_cfg=current_runtime,
                                queues_cfg=queuesCfg,
                                act_schema=ACT,
                                log=stdlog.getLogger("bridge.policy"),
                                emit_event=emit_event,
                                policy_step=(policy.act if hasattr(policy, "act") else policy),
                                on_latency_stats=on_latency_stats,
                            )
                        )
                    )

                ws_ready.set()

                if episode_start_time is None:
                    await start_new_episode(ws, obsQueue, obsState, obs_drop_policy)
                continue

            if kind == "observation":
                try:
                    validate(instance=msg, schema=OBS)
                except ValidationError as ve:
                    log.warning("obs failed schema", extra={"error": str(ve)})
                    continue

                await EnqueueObservation(obsQueue, msg, obsState, obs_drop_policy)
                continue

            if kind == "action_result":
                # Validate against the unified event schema (EVT) and then
                # perform minimal sanity checks before resolving the pending future.
                try:
                    validate(instance=msg, schema=EVT)
                except ValidationError as ve:
                    log.warning("action_result failed schema", extra={"error": str(ve), "raw": msg})
                    continue

                if "seq" not in msg:
                    log.warning("action_result missing seq", extra={"raw": msg})
                    continue

                try:
                    seq = int(msg["seq"])
                except Exception:
                    log.warning("action_result bad seq", extra={"raw": msg})
                    continue

                payload = msg.get("payload") or {}
                if not isinstance(payload, dict):
                    log.warning("action_result payload not dict", extra={"seq": seq})
                    continue

                res = payload.get("action_result")
                if not isinstance(res, dict):
                    log.warning(
                        "action_result missing payload.action_result",
                        extra={"seq": seq, "payload_keys": list(payload.keys())},
                    )
                    continue

                # Compatibility shim: forwarded -> success
                if res.get("status") == "forwarded":
                    res["status"] = "success"

                fut = pending.pop(seq, None)
                log.debug("resolve fut", extra={"seq": seq, "had_fut": fut is not None})
                if fut and not fut.done():
                    fut.set_result(res)

                continue

            if kind == "bridge_health":
                try:
                    validate(instance=msg, schema=EVT)
                except ValidationError as ve:
                    log.warning("event failed schema", extra={"error": str(ve)})
                continue

            if kind == "episode_end":
                try:
                    validate(instance=msg, schema=EVT)
                except ValidationError as ve:
                    log.warning("event failed schema", extra={"error": str(ve), "kind": kind})
                    continue

                payload = msg.get("payload") or {}
                body = payload.get("episode_end", {}) if isinstance(payload, dict) else {}
                reason = body.get("reason", "unknown")

                log.info("episode_end received", extra={"reason": reason, "episode": episode})

                # Notify policy side about episode_end
                await EnqueueObservation(obsQueue, msg, obsState, obs_drop_policy)

                # Start a new episode (also emits episode_start and enqueues synthetic start)
                await start_new_episode(ws, obsQueue, obsState, obs_drop_policy)
                continue

            if kind == "episode_start":
                # Client should not send episode_start; ignore.
                try:
                    validate(instance=msg, schema=EVT)
                except ValidationError as ve:
                    log.warning("event failed schema", extra={"error": str(ve), "kind": kind})
                log.info("episode_start from client ignored")
                continue

            if kind == "eval_control":
                # Evaluation runner: request start of next episode without sending episode_end.
                payload = msg.get("payload") or {}
                if isinstance(payload, dict) and payload.get("action") == "start_episode":
                    await start_new_episode(ws, obsQueue, obsState, obs_drop_policy)
                    log.debug("eval_control start_episode", extra={"ws_id": id(ws)})
                continue

            if kind == "config_update":
                # Hot-reload: merge payload into overlay, refresh current_runtime, apply to policy, persist to disk.
                payload = msg.get("payload")
                if isinstance(payload, dict):
                    new_overlay = DeepMerge(dict(runtime_overlay), dict(payload))
                    runtime_overlay.clear()
                    runtime_overlay.update(new_overlay)
                    refresh_current_runtime()
                    if policy is not None and hasattr(policy, "apply_runtime_config"):
                        try:
                            policy.apply_runtime_config(current_runtime)
                            log.info(
                                "config_update applied",
                                extra={"ws_id": id(ws), "keys": list(payload.keys())},
                            )
                        except Exception as e:
                            log.warning(
                                "config_update apply failed",
                                extra={"error": str(e), "trace": traceback.format_exc()},
                            )
                    save_runtime_overlay(runtime_overlay)
                continue

            log.warning("unknown kind", extra={"kind": kind})

    except (ConnectionClosed, ConnectionClosedOK, ConnectionClosedError):
        log.info("ws disconnected")

        # Immediately fail all pending waits
        for seq, fut in list(pending.items()):
            if not fut.done():
                fut.set_exception(ConnectionError("ws disconnected"))
        pending.clear()

    finally:
        stopEvt.set()

        # Save DQN checkpoint on disconnect so latest state is persisted (in addition to periodic saves)
        if policy is not None and hasattr(policy, "agent") and hasattr(policy, "ckpt_latest_path"):
            try:
                policy.agent.Save(policy.ckpt_latest_path)
                log.info("checkpoint saved on disconnect", extra={"path": policy.ckpt_latest_path})
            except Exception as e:
                log.warning("checkpoint save on disconnect failed", extra={"error": str(e)})

        # Fail pending on any exit path
        for seq, fut in list(pending.items()):
            if not fut.done():
                fut.set_exception(ConnectionError("ws closing"))
        pending.clear()

        for t in tasks:
            t.cancel()
        with contextlib.suppress(Exception):
            await asyncio.gather(*tasks, return_exceptions=True)


# Entrypoint
async def Main() -> None:
    global RESOLVED_METRICS_SINK_PATH
    LoadEpisodeNumber()

    env = os.getenv("APP_ENV", "prod")
    cfg = LoadConfig(env=env)

    RESOLVED_METRICS_SINK_PATH = resolve_metrics_sink_path(cfg, dataDir)

    # Configure logging once per process
    SetupLogging(cfg.bridge.get("logging", {}))

    serverCfg = cfg.bridge["server"]
    log = stdlog.getLogger("bridge.server")
    log.info(
        "starting server",
        extra={"env": env, "host": serverCfg["host"], "port": serverCfg["port"]},
    )

    async def handler(ws: WebSocketServerProtocol):
        return await Handle(ws, cfg)

    async with serve(
        handler,
        serverCfg["host"],
        serverCfg["port"],
        ping_interval=serverCfg.get("ping_interval_s", 5),
        ping_timeout=serverCfg.get("ping_timeout_s", 5),
        max_size=serverCfg.get("max_msg_bytes", 1048576),
    ):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(Main())