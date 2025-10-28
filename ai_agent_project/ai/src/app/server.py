# server.py  (Sprint-2 complete through Step 3)

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
    await ws.send(json.dumps(msg))


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

    async def PolicyLoop():
        """Run the navigation policy: consume obsQueue, produce actions."""
        while not stopEvt.is_set():
            try:
                obs = await obsQueue.get()
                acts = await policy.step(obs)
                for act in acts:
                    await actQueue.put(act)
            except Exception as e:
                log.warning("policy loop error", extra={"error": str(e)})
                await asyncio.sleep(0.1)

    # Register background loops
    tasks.append(asyncio.create_task(PolicyLoop()))
    tasks.append(asyncio.create_task(MetricsLoop(stopEvt, cfg, obsState, obsQueue, actState, actQueue)))
    tasks.append(asyncio.create_task(HeartBeatLoop(ws, stopEvt)))
    tasks.append(asyncio.create_task(ActionSenderLoop(stopEvt)))

    log.info("client connected", extra={"remote": getattr(ws, "remote_address", None)})

    # Helpers inside Handle 

    async def EnqueueAction(item: dict) -> None:
        """Block on action queue; never drop actions."""
        await actQueue.put(item)
        actState["actHighWatermark"] = max(actState["actHighWatermark"], actQueue.qsize())

    async def SendAction(actionMsg: dict, timeoutMs: int = 300) -> dict:
        """Validate, send, and await action_result."""
        validate(instance=actionMsg, schema=ACT)
        actionId = actionMsg.get("action_id") or actionMsg.get("payload", {}).get("action_id")
        if not actionId:
            raise ValueError("action_id missing in action message")
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        pending[actionId] = fut
        await ws.send(json.dumps(actionMsg))
        try:
            return await asyncio.wait_for(fut, timeout=timeoutMs / 1000.0)
        except asyncio.TimeoutError:
            actState["actionTimeouts"] += 1
            pending.pop(actionId, None)
            raise

    async def ActionSenderLoop(stopEvt: asyncio.Event) -> None:
        """Flush coalesced or discrete actions to the websocket at tick rate."""
        tickHz = cfg.runtime.get("policy", {}).get("tick_hz", 12)
        dt = max(1.0 / float(tickHz), 0.01)
        latestLook: Optional[dict] = None
        latestMove: Optional[dict] = None
        discrete: deque[dict] = deque()

        def IsContinuous(kind: str) -> bool:
            return coalCfg.get("enabled", True) and kind in set(coalCfg.get("kinds", ["look", "move"]))

        while not stopEvt.is_set():
            # Drain queue (non-blocking)
            try:
                while True:
                    item = actQueue.get_nowait()
                    payload = item.get("payload", {})
                    kinds = [
                        k for k in ("look","move","jump","sneak","attack","use","place","select_slot")
                        if k in payload
                    ]
                    if not kinds:
                        discrete.append(item)
                        continue
                    k = kinds[0]
                    if IsContinuous(k):
                        if k == "look":
                            latestLook = item
                        elif k == "move":
                            latestMove = item
                    else:
                        discrete.append(item)
            except asyncio.QueueEmpty:
                pass

            # Send one look and move per tick
            if latestLook:
                try: await SendAction(latestLook)
                except Exception: pass
                latestLook = None
            if latestMove:
                try: await SendAction(latestMove)
                except Exception: pass
                latestMove = None

            # Send limited discrete actions
            maxPerTick = cfg.runtime.get("policy", {}).get("max_actions_per_tick", 2)
            sent = 0
            while discrete and sent < maxPerTick:
                msg = discrete.popleft()
                try: await SendAction(msg)
                except Exception: pass
                sent += 1

            await asyncio.sleep(dt)

    tasks.append(asyncio.create_task(ActionSenderLoop(stopEvt)))



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
    asyncio.run(Main())
