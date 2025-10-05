import asyncio
import json
import websockets
from dummy import decide

uri = "ws://localhost:8765"

async def run_dummy():
    async with websockets.connect(uri) as ws:
        print("[DUMMY AI] Connected to AI bridge")

        async for message in ws:
            try:
                data = json.loads(message)
            except json.JSONDecodeError:
                print("[DUMMY AI] Bad JSON:", message)
                continue

            if data.get("type") == "observation":
                print("[DUMMY AI] Received observation")

                # Make a decision
                payload = decide(data)

                # Wrap decision in valid schema structure
                msg = {
                    "type": "action",
                    "schema_version": "v0",
                    "timestamp": data.get("timestamp", 0),
                    "seq": data.get("seq", 0),
                    "payload": payload
                }

                await ws.send(json.dumps(msg))
                print("[DUMMY AI] Sent action:", msg)

asyncio.run(run_dummy())
