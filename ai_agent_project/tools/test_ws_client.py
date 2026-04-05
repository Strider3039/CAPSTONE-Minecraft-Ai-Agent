import asyncio, websockets

async def test():
    uri = "ws://127.0.0.1:8765"
    async with websockets.connect(uri) as ws:
        print("Connected to server!")
        await ws.send('{"proto": "1", "kind": "ping"}')
        print(await ws.recv())

asyncio.run(test())
