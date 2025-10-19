from __future__ import annotations
import asyncio, time, statistics, sys, uuid
import pathlib as _pathlib
from typing import Any, Dict, Optional
from jsonschema import validate, ValidationError
from collections import deque

SRC = _pathlib.Path(__file__).resolve().parents[1]  # ai/src
if str(SRC) not in sys.path:
    sys.path.append(str(SRC))

from actions.codec import ClampAction  # keep if you still want extra safety
from policy.dummy import decide


async def QueueAdd(q: asyncio.Queue, item: Any) -> None:
    """Actions should never be dropped; block until there is space."""
    await q.put(item)


def IdlePayload() -> Dict[str, Any]:
    return {
        "look": {"dYaw": 0.0, "dPitch": 0.0},
        "move": {"forward": 0.0, "strafe": 0.0},
        "jump": False,
    }


def Percentile(sortedVals, p: float) -> float:
    if not sortedVals:
        return 0.0
    k = max(0, min(len(sortedVals) - 1, int(round(p * (len(sortedVals) - 1)))))
    return float(sortedVals[k])


def _aid() -> str:
    return uuid.uuid4().hex


def MakeLook(yawDelta: float, pitchDelta: float) -> Dict[str, Any]:
    return {
        "proto": "1",
        "kind": "action",
        "seq": 0,
        "timestamp": time.time(),
        "action_id": _aid(),
        "payload": {"look": {"dYaw": float(yawDelta), "dPitch": float(pitchDelta)}},
    }


def MakeMove(forward: float, strafe: float) -> Dict[str, Any]:
    return {
        "proto": "1",
        "kind": "action",
        "seq": 0,
        "timestamp": time.time(),
        "action_id": _aid(),
        "payload": {"move": {"forward": float(forward), "strafe": float(strafe)}},
    }


def MakeJump() -> Dict[str, Any]:
    return {
        "proto": "1",
        "kind": "action",
        "seq": 0,
        "timestamp": time.time(),
        "action_id": _aid(),
        "payload": {"jump": True},
    }


async def PolicyWorker(
    obs_q: asyncio.Queue,
    act_q: asyncio.Queue,
    drop_policy: str,        # kept for signature compatibility; ignored (we block)
    act_schema: dict,
    on_drop,                 # kept for signature compatibility; not used (no drops)
    log,
    emit_event=None,         # async callable(kind, payload) that must comply with event.schema.json
):
    """
    Runs at ~10 Hz. Each tick:
      - drains obs_q and keeps only the most recent observation
      - runs decide(obs) with a 100 ms budget
      - emits separate one-kind action messages (look, move, jump)
      - validates each action against act_schema
      - tracks latency and periodically emits a bridge_health 'latency_stats' info
    """
    seqOut = 0
    tickHz = 10.0
    tickDt = 1.0 / tickHz
    nextTick = time.time()

    latestObs: Optional[dict] = None
    latSamplesMs = deque(maxlen=200)  # ~20 s of samples @10 Hz
    lastStatsTs = time.time()
    loop = asyncio.get_running_loop()

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
        if now < nextTick:
            await asyncio.sleep(nextTick - now)
        nextTick += tickDt

        await DrainLatest()

        # If no observation yet, skip producing actions this tick
        if latestObs is None:
            continue

        obsTs = float(latestObs.get("timestamp", time.time()))
        try:
            decision = await asyncio.wait_for(
                loop.run_in_executor(None, decide, latestObs),
                timeout=0.100,
            )
            payload = decision if isinstance(decision, dict) else IdlePayload()
        except asyncio.TimeoutError:
            log.warning("decide() timed out; skipping tick")
            payload = IdlePayload()
            if emit_event:
                # standards-compliant event: bridge_health
                await emit_event("bridge_health", {"level": "warn", "detail": "decide_timeout"})
            continue
        except Exception as e:
            log.warning("decide() error; skipping tick", extra={"error": str(e)})
            payload = IdlePayload()
            if emit_event:
                await emit_event("bridge_health", {"level": "warn", "detail": f"decide_error:{e}"})
            continue

        # Track latency from observation timestamp
        latMs = max(0.0, (time.time() - obsTs) * 1000.0)
        latSamplesMs.append(latMs)

        # Split into one-action-per-message
        outMsgs: list[Dict[str, Any]] = []

        look = payload.get("look")
        if look and isinstance(look, dict):
            outMsgs.append(MakeLook(look.get("dYaw", 0.0), look.get("dPitch", 0.0)))

        move = payload.get("move")
        if move and isinstance(move, dict):
            outMsgs.append(MakeMove(move.get("forward", 0.0), move.get("strafe", 0.0)))

        if payload.get("jump"):
            outMsgs.append(MakeJump())

        # Validate + (optionally) clamp, then enqueue (block)
        for msg in outMsgs:
            # If ClampAction expects old envelope, you can remove this call.
            try:
                validate(instance=msg, schema=act_schema)
            except ValidationError as e:
                log.warning("outgoing action failed schema; dropping", extra={"error": str(e)})
                continue
            await QueueAdd(act_q, msg)
            seqOut += 1

        # Emit latency stats ~every 2 s (as bridge_health informational)
        if emit_event and (time.time() - lastStatsTs >= 2.0) and len(latSamplesMs) >= 5:
            samples = sorted(latSamplesMs)
            p50 = statistics.median(samples)
            p90 = Percentile(samples, 0.90)
            detail = f"latency_stats p50_ms={p50:.1f} p90_ms={p90:.1f} hz={tickHz:.0f}"
            await emit_event("bridge_health", {"level": "info", "detail": detail})
            lastStatsTs = time.time()
