# -----------------------------------------------------------------------------
# Shared fake WebSocket for bridge tests
# -----------------------------------------------------------------------------
import asyncio
import json

from websockets.exceptions import ConnectionClosedOK


class DummyWS:
    """Fake WebSocket that feeds scripted messages into Handle() and records what gets sent back."""

    def __init__(self, incoming, block_after=False, disconnect_after=False):
        self.sent_messages = []
        self._incoming = list(incoming)
        self._index = 0
        self._block_after = block_after
        self._disconnect_after = disconnect_after
        self._closed = False
        self._close_event = asyncio.Event()

        # Handle() logs remote_address if it exists.
        self.remote_address = ("127.0.0.1", 12345)

    async def send(self, data):
        if isinstance(data, (bytes, bytearray)):
            data = data.decode("utf-8")
        try:
            self.sent_messages.append(json.loads(data))
        except Exception:
            self.sent_messages.append(data)

    def close(self, code=None, reason=None):
        self._closed = True
        self._close_event.set()

    @property
    def closed(self):
        return self._closed

    def __aiter__(self):
        return self

    async def __anext__(self):
        if self._index < len(self._incoming):
            msg = self._incoming[self._index]
            self._index += 1
            await asyncio.sleep(0.06)  # give the policy worker a moment to tick
            return msg

        if self._disconnect_after:
            # pause briefly so background tasks can process the last message
            await asyncio.sleep(0.15)
            raise ConnectionClosedOK(None, "test disconnect")

        if self._block_after:
            self._close_event.clear()
            await self._close_event.wait()
            if self._closed:
                raise ConnectionClosedOK(None, "hello_timeout")
            raise asyncio.CancelledError()

        raise asyncio.CancelledError()
