from __future__ import annotations
import asyncio, logging
from typing import Any, Dict

class BasePolicy:
    """Shared base class for all agent policies."""
    def __init__(self, cfg: Dict[str, Any]):
        self.cfg = cfg
        self.log = logging.getLogger(self.__class__.__name__)

    async def step(self, obs: Dict[str, Any]) -> list[Dict[str, Any]]:
        raise NotImplementedError

    async def run(self, obs_q: asyncio.Queue, act_q: asyncio.Queue, stop_evt: asyncio.Event):
        """Main loop that consumes observations and emits actions."""
        tick_hz = self.cfg["runtime"]["policy"]["tick_hz"]
        dt = 1.0 / tick_hz
        while not stop_evt.is_set():
            try:
                obs = obs_q.get_nowait()
                acts = await self.step(obs)
                for act in acts:
                    await act_q.put(act)
            except asyncio.QueueEmpty:
                # no observation available, wait and try again
                await asyncio.sleep(dt)
                continue
            await asyncio.sleep(dt)
            continue
