import asyncio, websockets, json

async def main():
    uri = "ws://127.0.0.1:8765"
    async with websockets.connect(uri) as ws:
        msg = {
            "proto": "1",
            "kind": "action",
            "seq": 1,
            "timestamp": 0,
            "action_id": "manual_test_1",
            "payload": {
                "move": {"forward": 1.0, "strafe": 0.0},
                "look": {"dYaw": 45.0, "dPitch": 0.0}
            }
        }
        await ws.send(json.dumps(msg))
        print("Sent test action!")
        response = await ws.recv()
        print("Response:", response)

asyncio.run(main())
