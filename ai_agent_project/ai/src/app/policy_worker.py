from __future__ import annotations
import asyncio, time, statistics, sys
import pathlib as _pathlib
from typing import Any, Optional
import uuid
from jsonschema import validate, ValidationError
from collections import deque
import time

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

def normalize_action(action_msg: dict, seq_hint: int | None = None) -> dict:
    action_msg = dict(action_msg)  # shallow copy

    action_msg.setdefault("proto", "1")
    action_msg.setdefault("kind", "action")
    action_msg.setdefault("timestamp", time.time())

    # Ensure payload exists
    if "payload" not in action_msg or not isinstance(action_msg["payload"], dict):
        action_msg["payload"] = {}

    # Force top-level action_id (Java requires top-level)
    if not action_msg.get("action_id"):
        action_msg["action_id"] = f"p_{uuid.uuid4().hex[:10]}"

    # Ensure deadline_ms is top-level (schema + Java expects it there)
    if "deadline_ms" not in action_msg:
        action_msg["deadline_ms"] = 50

    return action_msg

DISCRETE_KEYS = {"attack", "use", "select_slot"}  # add more later (place_block, break_block, etc.)

def action_needs_ack(action_msg: dict) -> bool:
    payload = (action_msg.get("payload") or {})
    return any(k in payload for k in DISCRETE_KEYS)


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

    # at top of PolicyWorker
    last_move = None
    last_move_ts = 0.0

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
    
    last_payload = None


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

            action_msg = normalize_action(action_msg)
            payload = action_msg.get("payload") or {}

            RESEND_INTERVAL_S = 0.10  # 10 Hz; use 0.05 for 20 Hz if you want snappier control

            if not action_needs_ack(action_msg):
                now = time.time()
                if payload == last_payload and (now - last_send_ts) < RESEND_INTERVAL_S:
                    continue
                last_payload = payload
                last_send_ts = now


            # 2) Mark whether sender should await action_result
            action_msg["await_result"] = action_needs_ack(action_msg)

            # # 3) Coalesce controls if queue is backing up:
            # # Keep newest control action only (don't grow latency)
            # if not action_msg["await_result"]:
            #     # drain existing queued controls
            #     while True:
            #         try:
            #             act_q.get_nowait()
            #             act_q.task_done()
            #         except asyncio.QueueEmpty:
            #             break

            await QueueAdd(act_q, action_msg)


        except Exception as e:
            log.warning("policy_step failed", extra={"error": str(e)})
            if emit_event:
                await emit_event(
                    "bridge_health",
                    {"level": "warn", "detail": f"policy_step_error:{e}"},
                )
            continue

        latMs = max(0.0, (time.time() - obsTs) * 1000.0)
        latSamplesMs.append(latMs)

        if emit_event and (time.time() - lastStatsTs >= 2.0) and len(latSamplesMs) >= 5:
            samples = sorted(latSamplesMs)
            p50 = statistics.median(samples)
            p90 = Percentile(samples, 0.90)
            detail = f"latency_stats p50_ms={p50:.1f} p90_ms={p90:.1f} hz={tick_hz:.0f}"
            await emit_event("bridge_health", {"level": "info", "detail": detail})
            lastStatsTs = time.time()
