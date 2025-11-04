# server.py  (Sprint-2 complete through Step 3, updated for proper seq + policy fix)

import asyncio
import json
import os
import sys
import time
import pathlib
import logging as stdlog
import contextlib
from typing import Any, Dict, Optional
from collections import deque

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

from ai.src.policy.Goal_Nav_Policy import GoalNavPolicy
from policy_worker import PolicyWorker

# --- fast JSON encode/decode (prefers orjson) ---
try:
    import orjson as _fastjson

    def _dumps(obj):
        return _fastjson.dumps(obj)  # bytes

    def _loads(s):
        # websockets may give us str; orjson wants bytes
        return _fastjson.loads(s if isinstance(s, (bytes, bytearray)) else s.encode("utf-8"))

    _SEND_TEXT = False  # send binary frames for speed
except Exception:
    import json as _fastjson

    def _dumps(obj):
        # compact separators = smaller frames
        return _fastjson.dumps(obj, ensure_ascii=False, separators=(",", ":"))

    def _loads(s):
        return _fastjson.loads(s)

    _SEND_TEXT = True  # text frames


#  Schemas 

rootPath = pathlib.Path(__file__).resolve().parents[2]
sharedDir = rootPath.parent / "shared"
schemasDir = sharedDir / "schemas"

OBS = json.loads((schemasDir / "observation.schema.json").read_text("utf-8"))
ACT = json.loads((schemasDir / "action.schema.json").read_text("utf-8"))
EVT = json.loads((schemasDir / "event.schema.json").read_text("utf-8"))


#  Utilities 

async def SendEvents(ws: WebSocketServerProtocol, kind: str, payload: dict) -> None:
    """Emit an event that conforms to event.schema.json v1."""
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
    payload = _dumps(msg)
    await ws.send(payload)

async def SendCommand(ws: WebSocketServerProtocol, cmd: str) -> None:
    """Send a Minecraft command to the client (e.g., tp, say, time set day)."""
    msg = {
        "proto": "1",
        "kind": "command",
        "seq": int(time.time() * 1000),
        "timestamp": time.time(),
        "payload": {"cmd": cmd},
    }
    payload = _dumps(msg)
    await ws.send(payload)
    stdlog.getLogger("bridge.server").info("sent command", extra={"cmd": cmd})


async def EnqueueObservation(q: asyncio.Queue, item: dict, state: dict) -> None:
    """Put observation into bounded queue; drop oldest when full."""
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


async def MetricsLoop(
    stopEvt: asyncio.Event,
    cfg: Dict[str, Any],
    obsState: dict,
    obsQ: asyncio.Queue,
    actState: dict,
    actQ: asyncio.Queue,
) -> None:
    """Periodically write queue metrics to NDJSON sink if enabled."""
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
                    # observation stats
                    "queue_obs_size": obsQ.qsize(),
                    "queue_obs_high_watermark": obsState.get("obsHighWatermark", 0),
                    "obs_dropped": obsState.get("obsDropped", 0),
                    # action stats
                    "queue_act_size": actQ.qsize(),
                    "queue_act_high_watermark": actState.get("actHighWatermark", 0),
                    "action_timeouts": actState.get("actionTimeouts", 0),
                },
            )
        except Exception as e:
            log.warning("metrics write failed", extra={"error": str(e)})
        await asyncio.sleep(interval)


async def HeartBeatLoop(ws: WebSocketServerProtocol, stopEvt: asyncio.Event) -> None:
    """Emit periodic bridge_health pings so the client knows we're alive."""
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


#  Main Connection Handler 

async def Handle(ws: WebSocketServerProtocol) -> None:
    """WebSocket handler implementing observation + action pipelines."""
    log = stdlog.getLogger("bridge.server")
    cfg = LoadConfig(env=os.getenv("APP_ENV", "dev"))

    SetupLogging(cfg.bridge.get("logging", {}))

    queuesCfg = cfg.bridge.get("queues", {})
    obsMax = queuesCfg.get("obs_max", 128)
    obsQueue: asyncio.Queue[dict] = asyncio.Queue(maxsize=obsMax)
    obsState = {"obsDropped": 0, "obsHighWatermark": 0}

    # Action queue setup 
    actMax = queuesCfg.get("act_max", 64)
    actQueue: asyncio.Queue[dict] = asyncio.Queue(maxsize=actMax)
    actState = {"actHighWatermark": 0, "actionTimeouts": 0}
    actBehavior = queuesCfg.get("act_full_behavior", "block")
    coalCfg = queuesCfg.get("coalesce", {"enabled": True, "kinds": ["look", "move"]})
    pending: dict[str, asyncio.Future] = {}

    stopEvt = asyncio.Event()
    tasks: list[asyncio.Task] = []

    # --- Policy setup ---
    policy = GoalNavPolicy(cfg)

    async def send_command(cmd: str):
        await SendCommand(ws, cmd)

    policy.send_command = send_command

    asyncio.create_task(policy.evaluate(bridge=policy))

    seqCounter = 0

    async def _send_immediate(item: dict) -> None:
        try:
            # no ACK wait for continuous actions
            await SendAction(item, wait_for_result=False)
        except Exception as e:
            log.warning("immediate send failed", extra={"error": str(e)})

    def SendImmediate(item: dict) -> None:
        # fire-and-forget task
        asyncio.create_task(_send_immediate(item))

    async def emit_event(kind: str, payload: dict) -> None:
        await SendEvents(ws, kind, payload)

    # Helpers inside Handle 

    async def EnqueueAction(item: dict) -> None:
        """Block on action queue; never drop actions."""
        await actQueue.put(item)
        actState["actHighWatermark"] = max(actState["actHighWatermark"], actQueue.qsize())

    # at top you already have: from typing import Any, Dict, Optional

    async def SendAction(actionMsg: dict, timeoutMs: int = 300, wait_for_result: bool = True) -> Optional[dict]:
        """Validate, send, and (optionally) await action_result.

        For look/move we pass wait_for_result=False to avoid pending churn and
        any schema/action_id mismatches blocking the send.
        """
        nonlocal seqCounter
        seqCounter += 1
        actionMsg["seq"] = seqCounter
        actionMsg.setdefault("proto", "1")
        actionMsg.setdefault("kind", "action")
        actionMsg.setdefault("timestamp", time.time())

        if not wait_for_result:
            # Fire-and-forget: skip validation and correlation.
            await ws.send(json.dumps(actionMsg))
            return None

        # Awaiting a result → validate and require action_id so we can correlate.
        validate(instance=actionMsg, schema=ACT)

        actionId = actionMsg.get("action_id") or actionMsg.get("payload", {}).get("action_id")
        if not actionId:
            raise ValueError("action_id missing in action message (required when wait_for_result=True)")

        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        pending[actionId] = fut
        await ws.send(json.dumps(actionMsg))
        try:
            return await asyncio.wait_for(fut, timeout=timeoutMs / 1000.0)
        except asyncio.TimeoutError:
            actState["actionTimeouts"] += 1
            pending.pop(actionId, None)
            raise

    continuous_state = {
        "look": None,  # dict like {"dYaw": ..., "dPitch": ...} or None
        "move": None,  # dict like {"forward": ..., "strafe": ...} or None
    }

    def _is_zero_move(m):
        try:
            return float(m.get("forward", 0.0)) == 0.0 and float(m.get("strafe", 0.0)) == 0.0
        except Exception:
            return True

    def _is_zero_look(lk):
        try:
            return float(lk.get("dYaw", 0.0)) == 0.0 and float(lk.get("dPitch", 0.0)) == 0.0
        except Exception:
            return True

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
                next_deadline = time.perf_counter()  # snap forward if we drifted
        return sleep_exact


    async def ActionSenderLoop(stopEvt: asyncio.Event) -> None:
        """Drain actQueue: stash continuous (look/move) into state, pace discretes."""
        hz = max(20, int(cfg.runtime.get("policy", {}).get("tick_hz", 20)))  # align to MC
        sleep_exact = _exact_scheduler(hz)
        discrete: deque[dict] = deque()

        while not stopEvt.is_set():
            # Drain queued actions (non-blocking)
            try:
                while True:
                    item = actQueue.get_nowait()
                    payload = item.get("payload", {}) or {}

                    # Peel out continuous updates into the sticky state
                    lk = payload.get("look")
                    if isinstance(lk, dict):
                        continuous_state["look"] = lk if not _is_zero_look(lk) else None

                    mv = payload.get("move")
                    if isinstance(mv, dict):
                        continuous_state["move"] = mv if not _is_zero_move(mv) else None

                    # Anything *else* is discrete and should be paced/ACKed
                    discrete_keys = set(payload.keys()) - {"look", "move"}
                    if discrete_keys:
                        # strip continuous keys if present so discretes are clean
                        if "look" in payload or "move" in payload:
                            item = dict(item)
                            item["payload"] = {k: payload[k] for k in discrete_keys}
                        discrete.append(item)
            except asyncio.QueueEmpty:
                pass

            # Send limited discrete actions per tick (ACKed path)
            maxPerTick = int(cfg.runtime.get("policy", {}).get("max_actions_per_tick", 2))
            sent = 0
            while discrete and sent < maxPerTick:
                msg = discrete.popleft()
                if log.isEnabledFor(stdlog.DEBUG):
                    log.debug("sending discrete action")  # avoid heavy payload logging
                try:
                    await SendAction(msg)  # wait_for_result=True (default)
                except Exception as e:
                    log.warning("discrete send failed", extra={"error": str(e)})
                sent += 1

            await sleep_exact()


    async def ContinuousSenderLoop(stopEvt: asyncio.Event) -> None:
        """Re-emit latest look/move at ~20 Hz so motion feels like 'held' keys."""
        hz = max(20, int(cfg.runtime.get("policy", {}).get("tick_hz", 20)))
        sleep_exact = _exact_scheduler(hz)

        while not stopEvt.is_set():
            payload = {}
            lk = continuous_state["look"]
            mv = continuous_state["move"]

            if lk:
                payload["look"] = lk
            if mv:
                payload["move"] = mv

            if payload:
                msg = {
                    "proto": "1",
                    "kind": "action",
                    # seq will be filled in SendAction
                    "timestamp": time.time(),
                    "payload": payload,
                }
                # fire-and-forget, skip validation/ACK
                try:
                    await SendAction(msg, wait_for_result=False)
                except Exception as e:
                    log.warning("continuous send failed", extra={"error": str(e)})

            await sleep_exact()


    # Register background loops
    tasks.append(asyncio.create_task(MetricsLoop(stopEvt, cfg, obsState, obsQueue, actState, actQueue)))
    tasks.append(asyncio.create_task(HeartBeatLoop(ws, stopEvt)))
    tasks.append(asyncio.create_task(ActionSenderLoop(stopEvt)))
    tasks.append(asyncio.create_task(ContinuousSenderLoop(stopEvt)))


    tasks.append(asyncio.create_task(
        PolicyWorker(
            obs_q=obsQueue,
            act_q=actQueue,
            drop_policy="block",
            act_schema=ACT,
            on_drop=None,
            log=stdlog.getLogger("bridge.policy"),
            emit_event=emit_event,
            policy_step=policy.step,       # << scripted policy hook
        )
    ))


    # Main recv loop
    try:
        async for raw in ws:
            log.debug("recv", extra={"bytes": len(raw)})
            try:
                msg = json.loads(raw)
            except Exception as e:
                log.warning("recv/parse error", extra={"error": str(e)})
                await SendEvents(ws, "bridge_health", {"level": "warn", "detail": "invalid_json"})
                continue

            if msg.get("proto") != "1":
                log.warning("bad proto", extra={"got": msg.get("proto")})
                continue

            kind = msg.get("kind")
            if kind == "observation":
                try:
                    validate(instance=msg, schema=OBS)
                except ValidationError as ve:
                    log.warning("obs failed schema", extra={"error": str(ve)})
                    await SendEvents(ws, "bridge_health", {"level": "warn", "detail": "obs schema fail"})
                    continue
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


#  Entrypoint 

async def Main() -> None:
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
        await asyncio.Future()  # run forever


if __name__ == "__main__":
    while True:
        try:
            print("[SERVER] Starting server.py ...")
            asyncio.run(Main())
        except KeyboardInterrupt:
            print("[SERVER] Stopped manually.")
            break
        except Exception as e:
            print(f"[SERVER] Crashed with error: {e}")
            print("[SERVER] Restarting in 5 seconds...")
            time.sleep(5)
            continue
