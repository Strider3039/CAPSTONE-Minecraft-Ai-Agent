# server.py  (Sprint-2 complete through Step 3, updated for Data/ layout)

import asyncio
import json
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
from ai.src.utils.config import LoadConfig
from ai.src.utils.logging import SetupLogging, WriteMetric

from ai.src.policy.registry import build_policy_from_config
from ai.src.app.policy_worker import PolicyWorker

# ---------- Fast JSON ----------
try:
    import orjson as _fastjson

    def _dumps(obj):
        return _fastjson.dumps(obj)

    def _loads(s):
        return _fastjson.loads(
            s if isinstance(s, (bytes, bytearray)) else s.encode("utf-8")
        )

    _SEND_TEXT = False
except Exception:
    import json as _fastjson

    def _dumps(obj):
        return _fastjson.dumps(obj, ensure_ascii=False, separators=(",", ":"))

    def _loads(s):
        return _fastjson.loads(s)

    _SEND_TEXT = True

import orjson

def _dumps(obj) -> str:
    return orjson.dumps(obj).decode("utf-8")  # IMPORTANT: decode => TEXT

# ---------- Paths ----------
server_root = pathlib.Path(__file__).resolve()
project_root = server_root.parents[3]  # ai_agent_project

sharedDir = project_root / "shared"
schemasDir = sharedDir / "schemas"

dataDir = sharedDir / "Data"
dataDir.mkdir(parents=True, exist_ok=True)

OBS = json.loads((schemasDir / "observation.schema.json").read_text("utf-8"))
ACT = json.loads((schemasDir / "action.schema.json").read_text("utf-8"))
EVT = json.loads((schemasDir / "event.schema.json").read_text("utf-8"))

# ---------- Episode persistence ----------
EPISODE_SAVE_PATH = dataDir / "episode_state.json"
episode = 0
episode_start_time: Optional[float] = None
MC_DAY_SECONDS = 1200  # 20 min


def LoadEpisodeNumber() -> None:
    global episode
    log = stdlog.getLogger("bridge.server")
    try:
        if EPISODE_SAVE_PATH.exists():
            data = json.loads(EPISODE_SAVE_PATH.read_text())
            episode = int(data.get("episode", 0))
            log.info("Loaded episode", extra={"episode": episode})
    except Exception as e:
        log.warning("Failed to load episode persistence", extra={"error": str(e)})


def SaveEpisodeNumber() -> None:
    log = stdlog.getLogger("bridge.server")
    try:
        EPISODE_SAVE_PATH.write_text(json.dumps({"episode": episode}))
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
    except ValidationError as e:
        log.warning("internal event failed schema", extra={"error": str(e), "kind": kind})
    await ws.send(_dumps(msg))


async def EnqueueObservation(q: asyncio.Queue, item: dict, state: dict) -> None:
    try:
        q.put_nowait(item)
    except asyncio.QueueFull:
        try:
            _ = q.get_nowait()
            state["obsDropped"] = state.get("obsDropped", 0) + 1
            q.put_nowait(item)
        except Exception:
            state["obsDropped"] = state.get("obsDropped", 0) + 1
    state["obsHighWatermark"] = max(state.get("obsHighWatermark", 0), q.qsize())


async def start_new_episode(
    ws: WebSocketServerProtocol,
    obs_q: Optional[asyncio.Queue] = None,
    obs_state: Optional[dict] = None,
) -> None:
    """
    Increment episode counter, reset timer, and emit episode_start.

    NOTE: event.schema.json only allows a 'reason' string for episode_start,
    so we encode the episode number inside the reason string.
    The client will perform the actual world reset when it receives episode_start.

    NEW: also enqueue a synthetic episode_start event into obs_q so the
    PolicyWorker / DQN can see episode boundaries and update histories.
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

    # Also notify the policy side if queues were provided
    if obs_q is not None and obs_state is not None:
        synthetic_evt = {
            "proto": "1",
            "kind": "episode_start",
            "seq": 0,
            "timestamp": episode_start_time,
            "payload": {
                "episode_start": {
                    "reason": reason_str
                }
            },
        }
        await EnqueueObservation(obs_q, synthetic_evt, obs_state)


async def MetricsLoop(
    stopEvt: asyncio.Event,
    cfg: Dict[str, Any],
    obsState: dict,
    obsQ: asyncio.Queue,
    actState: dict,
    actQ: asyncio.Queue,
) -> None:
    log = stdlog.getLogger("bridge.server.MetricsLoop")
    metricsCfg = cfg.get("bridge", {}).get("metrics", {})
    if not metricsCfg.get("enabled", True):
        return

    sinkPath = metricsCfg.get("sink", {}).get("path")
    interval = metricsCfg.get("sample_interval_s", 2)
    if not sinkPath:
        return

    while not stopEvt.is_set():
        try:
            WriteMetric(
                sinkPath,
                {
                    "queue_obs_size": obsQ.qsize(),
                    "queue_obs_high_watermark": obsState.get("obsHighWatermark", 0),
                    "obs_dropped": obsState.get("obsDropped", 0),
                    "queue_act_size": actQ.qsize(),
                    "queue_act_high_watermark": actState.get("actHighWatermark", 0),
                    "action_timeouts": actState.get("actionTimeouts", 0),
                },
            )
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

async def Handle(ws: WebSocketServerProtocol) -> None:
    log = stdlog.getLogger("bridge.server")
    cfg = LoadConfig(env=os.getenv("APP_ENV", "dev"))

    SetupLogging(cfg.bridge.get("logging", {}))

    queuesCfg = cfg.bridge.get("queues", {})
    obsQueue: asyncio.Queue[dict] = asyncio.Queue(maxsize=queuesCfg.get("obs_max", 128))
    obsState = {"obsDropped": 0, "obsHighWatermark": 0}

    actQueue: asyncio.Queue[dict] = asyncio.Queue(maxsize=queuesCfg.get("act_max", 64))
    actState = {"actHighWatermark": 0, "actionTimeouts": 0}
    pending: dict[str, asyncio.Future] = {}

    stopEvt = asyncio.Event()
    tasks: list[asyncio.Task] = []

    # Load policy (DQN / OnlineDQN)
    runtime_cfg: Dict[str, Any] = getattr(cfg, "runtime", {})
    policy = build_policy_from_config(runtime_cfg)

    async def emit_event(kind: str, payload: dict) -> None:
        await SendEvents(ws, kind, payload)

    seqCounter = 0

    async def SendAction(actionMsg: dict, timeoutMs: int = 300, wait_for_result: bool = True):
        nonlocal seqCounter
        seqCounter += 1

        # ---- normalize envelope
        actionMsg["seq"] = seqCounter
        actionMsg.setdefault("proto", "1")
        actionMsg.setdefault("kind", "action")
        actionMsg.setdefault("timestamp", time.time())

        # ---- REQUIRED by schema + Java: action_id must be TOP-LEVEL
        if not actionMsg.get("action_id"):
            actionMsg["action_id"] = f"a{seqCounter}_{uuid.uuid4().hex[:8]}"

        # ---- deadline_ms is top-level in your schema
        if "deadline_ms" not in actionMsg:
            actionMsg["deadline_ms"] = 50

        # ---- ensure payload exists
        if "payload" not in actionMsg or not isinstance(actionMsg["payload"], dict):
            actionMsg["payload"] = {}

        # validate AFTER normalization
        validate(instance=actionMsg, schema=ACT)

        actionId = actionMsg["action_id"]

        if not wait_for_result:
            await ws.send(json.dumps(actionMsg))  # TEXT
            return None

        fut = asyncio.get_running_loop().create_future()
        pending[actionId] = fut

        await ws.send(json.dumps(actionMsg))  # TEXT

        try:
            return await asyncio.wait_for(fut, timeout=timeoutMs / 1000)
        except asyncio.TimeoutError:
            actState["actionTimeouts"] += 1
            pending.pop(actionId, None)
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
        hz = max(20, int(cfg.runtime.get("policy", {}).get("tick_hz", 20)))
        sleep_exact = _exact_scheduler(hz)

        while not stopEvt.is_set():
            maxPerTick = int(cfg.runtime.get("policy", {}).get("max_actions_per_tick", 2))
            sent = 0

            while sent < maxPerTick:
                try:
                    item = actQueue.get_nowait()
                except asyncio.QueueEmpty:
                    break

                try:
                    await SendAction(item)
                except Exception as e:
                    log.warning("action send failed", extra={"error": repr(e), "trace": traceback.format_exc()},)


                sent += 1

            await sleep_exact()

    # Background tasks
    tasks.append(asyncio.create_task(MetricsLoop(stopEvt, cfg, obsState, obsQueue, actState, actQueue)))
    tasks.append(asyncio.create_task(HeartBeatLoop(ws, stopEvt)))
    tasks.append(asyncio.create_task(ActionSenderLoop(stopEvt)))

    # RL worker
    tasks.append(
        asyncio.create_task(
            PolicyWorker(
                obs_q=obsQueue,
                act_q=actQueue,
                drop_policy="block",
                act_schema=ACT,
                on_drop=None,
                log=stdlog.getLogger("bridge.policy"),
                emit_event=emit_event,
                policy_step=policy.act,
            )
        )
    )

    # Start first episode when connection opens
    await start_new_episode(ws, obsQueue, obsState)

    # Receive loop

    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except Exception:
                await SendEvents(ws, "bridge_health", {"level": "warn", "detail": "invalid_json"})
                continue

            if msg.get("proto") != "1":
                continue

            kind = msg.get("kind")

            if kind == "observation":
                try:
                    validate(instance=msg, schema=OBS)
                except ValidationError as ve:
                    log.warning("obs failed schema", extra={"error": str(ve)})
                    continue

                # Old schema: payload already *is* the observation body (pose, rays, world, etc.)
                # We just enqueue the entire message for the policy.
                await EnqueueObservation(obsQueue, msg, obsState)

            elif kind == "action_result":
                try:
                    validate(instance=msg, schema=EVT)
                except ValidationError as ve:
                    log.warning("event failed schema", extra={"error": str(ve)})
                    continue

                res = msg["payload"]["action_result"]
                actionId = res["action_id"]

                fut = pending.pop(actionId, None)
                if fut and not fut.done():
                    fut.set_result(res)

            elif kind == "bridge_health":
                try:
                    validate(instance=msg, schema=EVT)
                except ValidationError as ve:
                    log.warning("event failed schema", extra={"error": str(ve)})
                    continue

            elif kind == "episode_end":
                # Client (Java) tells us an episode ended (death, timeout, manual reset).
                try:
                    validate(instance=msg, schema=EVT)
                except ValidationError as ve:
                    log.warning("event failed schema", extra={"error": str(ve), "kind": kind})
                    continue

                payload = msg.get("payload") or {}
                body = payload.get("episode_end", {})
                reason = body.get("reason", "unknown")

                log.info(
                    "episode_end received from client",
                    extra={"reason": reason, "episode": episode},
                )

                # ALSO notify the policy side about this episode_end so DQN can mark done
                await EnqueueObservation(obsQueue, msg, obsState)

                # Start a fresh episode: increments episode counter, saves, and
                # sends an episode_start event back down to the client and into obsQueue.
                await start_new_episode(ws, obsQueue, obsState)

            elif kind == "episode_start":
                # In the new design, the client SHOULD NOT send episode_start.
                # If it does, we just log and ignore (Python is the episode authority).
                try:
                    validate(instance=msg, schema=EVT)
                except ValidationError as ve:
                    log.warning("event failed schema", extra={"error": str(ve), "kind": kind})
                    continue

                log.info("episode_start event from client ignored")
                continue

            else:
                log.warning("unknown kind", extra={"kind": kind})

    except (ConnectionClosed, ConnectionClosedOK, ConnectionClosedError):
        log.info("client disconnected")

    finally:
        stopEvt.set()
        for t in tasks:
            t.cancel()
        with contextlib.suppress(Exception):
            await asyncio.gather(*tasks, return_exceptions=True)


# Entrypoint

async def Main() -> None:
    LoadEpisodeNumber()

    cfg = LoadConfig(env=os.getenv("APP_ENV", "dev"))
    SetupLogging(cfg.bridge.get("logging", {}))

    serverCfg = cfg.bridge["server"]
    log = stdlog.getLogger("bridge.server")
    log.info("starting server", extra={"host": serverCfg["host"], "port": serverCfg["port"]})

    async with serve(
        Handle,
        serverCfg["host"],
        serverCfg["port"],
        ping_interval=serverCfg.get("ping_interval_s", 5),
        ping_timeout=serverCfg.get("ping_timeout_s", 5),
        max_size=serverCfg.get("max_msg_bytes", 1048576),
    ):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(Main())
