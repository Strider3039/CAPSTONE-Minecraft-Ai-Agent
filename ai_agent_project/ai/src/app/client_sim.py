# client_sim_v0.py
from __future__ import annotations
import asyncio, json, time, contextlib
import websockets

WS_URL = "ws://localhost:8765"

# Pretty-printing utility for debugging
def pretty(obj):
    try:
        return json.dumps(obj, indent=2, ensure_ascii=False)
    except TypeError:
        return str(obj)

def now_s() -> float:
    # Use seconds (float). Switch to ms if your server expects that.
    return time.time()

def obs_v0(seq: int) -> dict:
    return {
        "type": "observation",
        "schema_version": "v0",
        "timestamp": now_s(),
        "seq": seq,
        "payload": {
            "pose": {
                "pos": {"x": 0.0, "y": 64.0, "z": 0.0},
                "yaw": 0.0,
                "pitch": 0.0
            },
            "rays": [2.0, 3.2, 5.5],           # 3–5 forward rays
            "hotbar": [None] * 9               # exactly 9 slots
        }
    }

def obs_invalid_missing_version(seq: int) -> dict:
    # Purposely missing schema_version to trigger schema_mismatch
    return {
        "type": "observation",
        "timestamp": now_s(),
        "seq": seq,
        "payload": {
            "pose": {
                "pos": {"x": 0.0, "y": 64.0, "z": 0.0},
                "yaw": 0.0,
                "pitch": 0.0
            },
            "rays": [1.0, 1.0, 1.0],
            "hotbar": [None] * 9
        }
    }

def action_v0(seq: int) -> dict:
    return {
        "type": "action",
        "schema_version": "v0",
        "timestamp": now_s(),
        "seq": seq,
        "payload": {
            "look": {"dYaw": 10.0, "dPitch": -5.0},  # will be clamped by server if needed
            "move": {"forward": 1.0, "strafe": 0.0}, # in [-1,1]
            "jump": False
        }
    }

async def main():
    rtt = {}  # seq -> send_time

    async with websockets.connect(WS_URL) as ws:
        print(">> Connected to", WS_URL)

        # Some servers send a greeting/event on connect; tolerate if not
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=0.5)
            print("<<", msg)
        except asyncio.TimeoutError:
            pass

        async def recv_loop():
            try:
                async for raw in ws:
                    try:
                        msg = json.loads(raw)
                    except json.JSONDecodeError:
                        print("<< (non-JSON):", raw)
                        continue

                    mtype = msg.get("type")
                    if mtype == "event":
                        kind = msg.get("kind")
                        payload = msg.get("payload", {})
                        if kind == "schema_mismatch" and isinstance(payload.get("reason"), str):
                            print("<< EVENT schema_mismatch:")
                            print(payload["reason"])  # raw string → nice multi-line output
                        else:
                            print(f"<< EVENT {kind}")
                            print(json.dumps(payload, indent=2, ensure_ascii=False))

                    elif mtype == "action":
                        # Server→client actions (10 Hz) — print cleanly
                        print("<< ACTION")
                        print(json.dumps(msg.get("payload", {}), indent=2, ensure_ascii=False))

                    elif mtype == "observation":
                        # Unusual from server; just show briefly
                        print("<< OBS (from server?)")
                        print(json.dumps(msg.get("payload", {}), indent=2, ensure_ascii=False))

                    else:
                        print("<< UNKNOWN MESSAGE")
                        print(json.dumps(msg, indent=2, ensure_ascii=False))
            except Exception as e:
                print("recv_loop error:", e)

        async def send_and_track(msg: dict):
            seq = msg.get("seq")
            rtt[seq] = now_s()
            await ws.send(json.dumps(msg))
            print(f">> SENT {msg['type']} seq={seq}")

        recv_task = asyncio.create_task(recv_loop())

        # 1) Valid observation -> expect ack
        await send_and_track(obs_v0(1))
        await asyncio.sleep(0.3)

        # 2) Invalid observation (no schema_version) -> expect schema_mismatch
        bad = obs_invalid_missing_version(2)
        await ws.send(json.dumps(bad))
        print(f">> SENT invalid observation seq={bad['seq']}")
        await asyncio.sleep(0.3)

        # 3) Valid action -> validate control-style payload; ack optional
        await send_and_track(action_v0(3))
        await asyncio.sleep(0.3)

        # 4) Hang briefly to catch heartbeat events if implemented
        await asyncio.sleep(3.0)

        recv_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await recv_task

if __name__ == "__main__":
    asyncio.run(main())
