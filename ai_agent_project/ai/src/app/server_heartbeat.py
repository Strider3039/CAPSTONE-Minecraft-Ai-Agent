import asyncio, json, time, logging as stdlog

log = stdlog.getLogger("bridge.heartbeat")

async def SendJson(ws, msg: dict):
    await ws.send(json.dumps(msg))

def event(msg_kind: str, payload: dict) -> dict:
    return {
        "type": "event",
        "schema_version": "v1",
        "timestamp": time.time(),
        "kind": msg_kind,
        "payload": payload,
    }

async def HeartbeatLoop(ws, server_start_ts: float, stop_evt: asyncio.Event):
    try:
        while not stop_evt.is_set():
            await asyncio.sleep(2.0)
            uptime = time.time() - server_start_ts
            heartBeat = event("heartbeat", {"uptime_s": uptime})
            await SendJson(ws, heartBeat)
    except Exception as e:
        log.warning("heartbeat loop error", extra={"error": str(e)})