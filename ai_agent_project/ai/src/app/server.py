from __future__ import annotations
import asyncio, json, logging, os, time, pathlib
from websockets.server import serve, WebScketServerProtocol
from websockets.exceptions import ConnectionClosed
from jsonschema import validate, ValidationError

from utils.config import LoadConfig
from utils.logging import SetupLogging

log = logging.getLogger("bridge.server")

# Define the path to the JSON schema files
root = pathlib.Path(__file__).resolve().parents[2]
schemas = root.parent / "shared" / "schema"

# Load the JSON schemas
OBS = json.loads((schemas / "observation.schema.json").read_text("utf-8"))
ACT = json.loads((schemas / "action.schema.json").read_text("utf-8"))
EVT = json.loads((schemas / "event.schema.json").read_text("utf-8"))

# Utility to send well-formed events to the client
async def SendEvents(ws: WebScketServerProtocol, kind: str, payload: dict) -> None:
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
        log.warning("internal event failed schema", extra={"error": str(e), "kine": kind})
    await ws.send(json.dumps(msg))

# Handle the WebSocket connection
async def Handle(ws: WebScketServerProtocol):
    peer = f"{ws.remote_address[0]:{ws.remote_address[1]}}"
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
                elif myType == "action":
                    validate(instance=msg, schema=ACT)
                    log.info("valid action", extra={"seq": msg.get("seq")})
                elif myType == "event":
                    validate(instance=msg, schema=EVT)
                    log.inf0("valid event", extra={"kind": msg.get("kind")})
                else:
                    raise ValidationError(f"Unknown type '{myType}'")
            
            except ValidationError as e:
                # Send a schema_mismatch event back to the client
                log.warning("schema validation failed", extra={"error": str(e)})
                await SendEvents(ws, "schema_mismatch", {"reason": str(e)})
    except:
        log.info("client disconnectewd", extra={"peer": peer})

async def Main():

    cfg = LoadConfig()