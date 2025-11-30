from __future__ import annotations
import asyncio, time, statistics, sys
import pathlib as _pathlib
from typing import Any, Optional
from jsonschema import validate, ValidationError
from collections import deque

# Make sure ai/src is on path if needed
SRC = _pathlib.Path(__file__).resolve().parents[1]  # ai/src
if str(SRC) not in sys.path:
    sys.path.append(str(SRC))


async def QueueAdd(q: asyncio.Queue, item: Any) -> None:
    """Actions should never be dropped; block until there is space."""
    await q.put(item)


def Percentile(sortedVals, p: float) -> float:
    if not sortedVals:
        return 0.0
    k = max(0, min(len(sortedVals) - 1, int(round(p * (len(sortedVals) - 1)))))
    return float(sortedVals[k])

async def PolicyWorker(
    obs_q: asyncio.Queue,
    act_q: asyncio.Queue,
    drop_policy: str,        # unused but kept for compatibility
    act_schema: dict,
    on_drop,                 # unused
    log,
    emit_event=None,         # async callable(kind, payload)
    policy_step=None,        # e.g., DQNPolicy.act or OnlineDQNPolicy.act
):
    """
    Main RL loop:

    - Pull most recent observation message from obs_q
    - Call policy_step(obsMsg) → action message dict
    - Validate action JSON (matches action.schema.json)
    - Enqueue for server → Minecraft
    - Emit latency stats every ~2 seconds
    """

    tick_hz = 20.0
    tick_dt = 1.0 / tick_hz
    next_tick = time.time()

    latestObs: Optional[dict] = None
    latSamplesMs = deque(maxlen=200)
    lastStatsTs = time.time()

    async def DrainLatest() -> bool:
        nonlocal latestObs
        drained = False
        while True:
            try:
                item = obs_q.get_nowait()
            except asyncio.QueueEmpty:
                break
            else:
                latestObs = item
                obs_q.task_done()
                drained = True
        return drained

    while True:
        now = time.time()
        if now < next_tick:
            await asyncio.sleep(next_tick - now)
        next_tick += tick_dt

        await DrainLatest()
        if latestObs is None:
            continue

        obsTs = float(latestObs.get("timestamp", time.time()))

        try:
            if policy_step is None:
                raise RuntimeError("policy_step was not provided to PolicyWorker")

            # policy_step expects the full observation message
            action_msg = policy_step(latestObs)

        except Exception as e:
            log.warning("policy_step failed", extra={"error": str(e)})
            if emit_event:
                await emit_event(
                    "bridge_health",
                    {"level": "warn", "detail": f"policy_step_error:{e}"},
                )
            continue

        try:
            validate(instance=action_msg, schema=act_schema)
        except ValidationError as ve:
            log.warning("DQN action failed schema", extra={"error": str(ve)})
            continue

        await QueueAdd(act_q, action_msg)

        latMs = max(0.0, (time.time() - obsTs) * 1000.0)
        latSamplesMs.append(latMs)

        if emit_event and (time.time() - lastStatsTs >= 2.0) and len(latSamplesMs) >= 5:
            samples = sorted(latSamplesMs)
            p50 = statistics.median(samples)
            p90 = Percentile(samples, 0.90)
            detail = f"latency_stats p50_ms={p50:.1f} p90_ms={p90:.1f} hz={tick_hz:.0f}"
            await emit_event("bridge_health", {"level": "info", "detail": detail})
            lastStatsTs = time.time()
