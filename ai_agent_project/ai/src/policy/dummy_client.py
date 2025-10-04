import asyncio
import json
import websockets
from dummy import decide

async def run_dummy():
    uri = "ws://localhost:8765"
    async with websockets.connect(uri) as ws:
        print("[DUMMY ai] Connected to AI bridge")

        async for message in ws:
            data = json.loads(message)

            if data.get("type") == "observation":
                print("[DUMMY ai] Received observation", data)

                action_set = decide(data)

                for action_name, params in action_set.items():
                    msg = {
                        "action": action_name,
                        "params": params if isinstance(params, dict) else {}
                    }
                    await ws.send(json.dumps(msg))
                    print(f"[DUMMY ai] Sent action: {msg}")

asyncio.run(run_dummy())
