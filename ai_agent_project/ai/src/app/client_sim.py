from __future__ import annotations
import asyncio, json, time
import websockets

WS_URL = "ws://localhost:8765"

# Connect to the WebSocket server and send/receive messages
async def main():
    async with websockets.connect(WS_URL) as ws:
        print(" >> Connected to", WS_URL)

        # Listen for the connected event
        msg = await ws.recv()
        print("<<", msg)

        # Send a test valid observation message
        validObs = {
            "type": "observation",
            "timestamp": int(time.time() * 1000),
            "seq": 1,
            "schema": "v1",
            "payload": {
                "position": {"x": 0.0, "y": 64.0, "z": 0.0},
                "health": 20,
                "nearby": []
            }
        }
        await ws.send(json.dumps(validObs))
        print(">> Sent valid observation")
        print("<<", await ws.recv()) # Expecting an ack response

        # Send a test invalid observation message (missing health)
        invalidObs = {
            "type": "observation",
            "timestamp": int(time.time() * 1000),
            "seq": 2,
            "schema": "v1",
            "payload": {
                "position": {"x": 0.0, "y": 64.0, "z": 0.0},
                "nearby": []
            }
        }