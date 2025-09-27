from __future__ import annotations
import asyncio, json, logging, os, time, pathlib
from websockets.server import serve, WebSocketServerProtocol
from websockets.exceptions import ConnectionClosed
from jsonschema import validate, ValidationError
import sys
import pathlib as _pathlib
import logging as stdlog
from typing import Any, Dict

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
                "timestamp": int(time.time() * 1000),
                "seq": seq_out,
                "schema": "v1",
                "payload": payload
            }
            
            msg = ClampAction(msg)
            try:
                validate(instance=msg, schema=act_schema)
            except ValidationError as e:
                log.warning("outgoing action failed schema", extra={"error": str(e)})
                # Keep going, don't send invalid actions
                msg["payload"] = {"kind": "noop", "args": {}}
            
            await QueueAdd(act_q, msg, drop_policy, on_drop)
            seq_out += 1
        
        finally:
            obs_q.task_done()

# Handle the WebSocket connection
async def Handle(ws: WebSocketServerProtocol):
    # remote_address is a (host, port) tuple
    try:
        peer = f"{ws.remote_address[0]}:{ws.remote_address[1]}"
    except Exception:
        peer = str(ws.remote_address)
    log.info("client connected", extra={"peer": peer})

    await SendEvents(ws, "connected", {"server": "ai-bridge", "version": "mvp1"})

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