from __future__ import annotations
import asyncio, json, logging, os, time, pathlib
from websockets.server import serve, WebSocketServerProtocol
from websockets.exceptions import ConnectionClosed, ConnectionClosedOK, ConnectionClosedError
from jsonschema import validate, ValidationError
import sys
import pathlib as _pathlib
import logging as stdlog
from typing import Any, Dict
import contextlib

#Server start time
SERVER_START_TS = time.time()

# Ensure utils can be imported by appending the absolute utils path
SRC = _pathlib.Path(__file__).resolve().parents[1] # ai/src
if str(SRC) not in sys.path:
    sys.path.append(str(SRC))
from utils import config, logging  

from utils.config import LoadConfig
from utils.logging import SetupLogging
from actions.codec import ClampAction
from policy.dummy import decide

log = stdlog.getLogger("bridge.server")

# Define the path to the JSON schema files
# __file__ -> ai/src/app/server.py
# parents[1] -> ai/src, parents[2] -> ai, so parent of that is project root
root = pathlib.Path(__file__).resolve().parents[2]
schemas = root.parent / "shared" / "schemas"

# Load the JSON schemas
OBS = json.loads((schemas / "observation.schema.json").read_text("utf-8"))
ACT = json.loads((schemas / "action.schema.json").read_text("utf-8"))
EVT = json.loads((schemas / "event.schema.json").read_text("utf-8"))

# Utility to send well-formed events to the client
async def SendEvents(ws: WebSocketServerProtocol, kind: str, payload: dict) -> None:
    msg = {
        "type": "event",
        "schema_version": "v0",
        "timestamp": time.time(),
        "kind": kind,
        "payload": payload,
    }

    # Validate the event against the schema before sending
    try:
        validate(instance=msg, schema=EVT)
    except ValidationError as e:
        log.warning("internal event failed schema", extra={"error": str(e), "kind": kind})
    await ws.send(json.dumps(msg))

# Create a heartbeat that pings the server and checks for responsiveness
async def HeartBeatLoop(ws: WebSocketServerProtocol, stop_evt: asyncio.Event):
    try:
        # emit one immediately so clients see liveness right away
        await SendEvents(ws, "heartbeat", {"uptime_s": time.time() - SERVER_START_TS})
        while not stop_evt.is_set():
            await asyncio.sleep(2.0)
            await SendEvents(ws, "heartbeat", {"uptime_s": time.time() - SERVER_START_TS})
    except (asyncio.CancelledError, ConnectionClosed, ConnectionClosedOK, ConnectionClosedError):
        # normal shutdown/close
        pass
    except Exception as e:
        log.warning("heartbeat loop error", extra={"error": str(e)})

# Safe implementation of adding items to a queue
async def QueueAdd(q: asyncio.Queue, item: Any, drop_policy: str, on_drop):
    try:
        q.put_nowait(item)
    except asyncio.QueueFull:
        if drop_policy == "oldest":
            q.get_nowait()  
            
            # on_drop polict might be implemented later
            if on_drop: await on_drop("oldest")
            q.put_nowait(item)
        elif drop_policy == "newest":
            if on_drop: await on_drop("newest")
        else:
            await q.put(item)

async def PolicyWorker(obs_q: asyncio.Queue, act_q: asyncio.Queue, drop_policy: str, act_schema: dict, on_drop, log):
    seq_out = 0
    while True:
        obs = await obs_q.get()
        try:
            payload = decide(obs)

            msg = {
                "type": "action",
                "timestamp": time.time(),
                "seq": seq_out,
                "schema_version": "v0",
                "payload": payload
            }
            
            msg = ClampAction(msg)
            try:
                validate(instance=msg, schema=act_schema)
            except ValidationError as e:
                log.warning("outgoing action failed schema", extra={"error": str(e)})
                # Send safe idle/noop that matches action schema
                msg["payload"] = {
                    "look": {"dYaw": 0.0, "dPitch": 0.0},
                    "move": {"forward": 0.0, "strafe": 0.0},
                    "jump": False
                }
            
            await QueueAdd(act_q, msg, drop_policy, on_drop)
            seq_out += 1
        
        finally:
            obs_q.task_done()
        
# Drains the action queue and sends actions to the client
async def SendActions(ws: WebSocketServerProtocol, act_q: asyncio.Queue, log):
    while True:
        msg = await act_q.get()
        try:
            await ws.send(json.dumps(msg))
            log.debug("sent action", extra={"seq": msg.get("seq")})
        finally:
            act_q.task_done()

async def OnDropEvent(ws: WebSocketServerProtocol, kind: str, why: str, qsize: int):
    await SendEvents(ws, "dropped", {"kind": kind, "policy": why, "qsize": qsize})

# Handle the WebSocket connection
async def Handle(ws: WebSocketServerProtocol):
    
    # remote_address is a (host, port) tuple
    try:
        peer = f"{ws.remote_address[0]}:{ws.remote_address[1]}"
    except Exception:
        peer = str(ws.remote_address)
    log.info("client connected", extra={"peer": peer})

    cfg = LoadConfig(env=os.getenv("APP_ENV", "dev"))
    runTime = getattr(cfg, "runtime", None) or (cfg.get("runtime", {}) if isinstance(cfg, dict) else {})

    obsQueueSize = runTime.get("obs_queue_size", 100)
    actQueueSize = runTime.get("act_queue_size", 100)
    dropPolicy = runTime.get("drop_policy", "oldest")

    obsQueue: asyncio.Queue = asyncio.Queue(maxsize=obsQueueSize)
    actQueue: asyncio.Queue = asyncio.Queue(maxsize=actQueueSize)

    # Not yet added to schema
    # await SendEvents(ws, "connected", {"server": "ai-bridge", "version": "mvp1"})

    # Add Heartbeat logic to Handle()
    stop_evt = asyncio.Event()
    hb_task = asyncio.create_task(HeartBeatLoop(ws, stop_evt))

    # Add Policy Worker logic to handle
    policyTask = asyncio.create_task(
        PolicyWorker(
            obs_q=obsQueue,
            act_q=actQueue,
            drop_policy=dropPolicy,
            act_schema=ACT,
            on_drop=lambda why: OnDropEvent(ws, "action", why, actQueue.qsize()),
            log=log,
        )
    )
    senderTask = asyncio.create_task(SendActions(ws, actQueue, log))

    try:
        # Start an async loop to receive messages
        async for raw in ws:
            log.debug("recv", extra={"bytes": len(raw)})
            
            # Try to parse the incoming message as JSON
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                log.warning("bad json", extra={"raw": raw[:50]})
                await SendEvents(ws, "schema_mismatch", {"reason": "invalid_json"})
                continue
            
            # Validate the message against the observation schema
            try:
                myType = msg.get("type")
                if myType == "observation":
                    validate(instance=msg, schema=OBS)
                    log.info("valid observation", extra={"seq": msg.get("seq")})
                    await SendEvents(ws, "ack", {"seq": msg.get("seq")})
                    await QueueAdd(
                        obsQueue, msg, dropPolicy,
                        on_drop=lambda why: OnDropEvent(ws, "observation", why, obsQueue.qsize())
                    )
                    
                elif myType == "action":
                    validate(instance=msg, schema=ACT)
                    log.info("valid action", extra={"seq": msg.get("seq")})
                    await SendEvents(ws, "ack", {"seq": msg.get("seq")})
                    
                elif myType == "event":
                    validate(instance=msg, schema=EVT)
                    log.info("valid event", extra={"kind": msg.get("kind")})
                else:
                    raise ValidationError(f"Unknown type '{myType}'")
            
            except ValidationError as e:
                # Send a schema_mismatch event back to the client
                log.warning("schema validation failed", extra={"error": str(e)})
                await SendEvents(ws, "schema_mismatch", {"reason": str(e)})

    except ConnectionClosed:
        log.info("client disconnected", extra={"peer": peer})
    except Exception:
        log.exception("unexpected error handling client", extra={"peer": peer})
    finally:
        stop_evt.set()
        for t in (policyTask, senderTask, hb_task):
            t.cancel()
        with contextlib.suppress(Exception):
            await asyncio.gather(policyTask, senderTask, hb_task, return_exceptions=True)


async def Main():

    cfg = LoadConfig(env=os.getenv("APP_ENV", "dev"))
    SetupLogging(cfg.logging["level"], cfg.logging.get("json", True))

    host = cfg.server["host"]
    port = cfg.server["port"]

    async with serve(
        Handle, host, port,
        ping_interval=cfg.server["ping_interval_s"],
        ping_timeout=cfg.server["ping_timeout_s"],
        max_size=cfg.server["max_msg_bytes"]
    ):
        log.info("ws server started", extra={"host": host, "port": port})
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    asyncio.run(Main())