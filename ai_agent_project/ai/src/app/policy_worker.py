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


# --- change the function signature (add policy_step: Optional[callable]) ---
async def PolicyWorker(
    obs_q: asyncio.Queue,
    act_q: asyncio.Queue,
    drop_policy: str,        # kept for signature compatibility; ignored (we block)
    act_schema: dict,
    on_drop,                 # kept for signature compatibility; not used (no drops)
    log,
    emit_event=None,         # async callable(kind, payload) that must comply with event.schema.json
    policy_step=None,        # << NEW: e.g., GoalNavPolicy.step
):
    """
    Runs at ~10 Hz. Each tick:
      - drains obs_q and keeps only the most recent observation
      - if policy_step is provided: calls policy_step(obs) (scripted policy)
        else: runs decide(obs) with a 100 ms budget
      - enqueues action messages
      - tracks latency and periodically emits a bridge_health 'latency_stats' info
    """
    seqOut = 0
    tickHz = 20.0
    tickDt = 1.0 / tickHz
    nextTick = time.time()

    latestObs: Optional[dict] = None
    latSamplesMs = deque(maxlen=200)
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
        if latestObs is None:
            continue

        obsTs = float(latestObs.get("timestamp", time.time()))

        # --- NEW: call scripted policy if provided; else fallback to decide() path ---
        try:
            if policy_step is not None:
                # scripted policy path (GoalNavPolicy.step): returns list of already-built action messages
                acts = await asyncio.wait_for(policy_step(latestObs), timeout=0.100)
                if not isinstance(acts, list):
                    acts = []  # be safe
                outMsgs = acts
            else:
                # fallback to your existing dummy decide() producing payload dict
                decision = await asyncio.wait_for(
                    loop.run_in_executor(None, decide, latestObs),
                    timeout=0.100,
                )
                payload = decision if isinstance(decision, dict) else IdlePayload()

                # Build action messages from payload (unchanged from your current code)
                outMsgs: list[Dict[str, Any]] = []
                look = payload.get("look")
                if look and isinstance(look, dict):
                    outMsgs.append(MakeLook(look.get("dYaw", 0.0), look.get("dPitch", 0.0)))
                move = payload.get("move")
                if move and isinstance(move, dict):
                    outMsgs.append(MakeMove(move.get("forward", 0.0), move.get("strafe", 0.0)))
                if payload.get("jump"):
                    outMsgs.append(MakeJump())
        except asyncio.TimeoutError:
            if emit_event:
                await emit_event("bridge_health", {"level": "warn", "detail": "policy_step_timeout"})
            continue
        except Exception as e:
            log.warning("policy step error; skipping tick", extra={"error": str(e)})
            if emit_event:
                await emit_event("bridge_health", {"level": "warn", "detail": f"policy_step_error:{e}"})
            continue

        # Track latency (unchanged)
        latMs = max(0.0, (time.time() - obsTs) * 1000.0)
        latSamplesMs.append(latMs)

        # Validate then enqueue (blocking); acts from GoalNavPolicy are already action messages
        for msg in outMsgs:
            try:
                validate(instance=msg, schema=act_schema)
            except ValidationError as e:
                log.warning("outgoing action failed schema; dropping", extra={"error": str(e)})
                continue
            await QueueAdd(act_q, msg)
            seqOut += 1

        # Emit latency stats (unchanged)
        if emit_event and (time.time() - lastStatsTs >= 2.0) and len(latSamplesMs) >= 5:
            samples = sorted(latSamplesMs)
            p50 = statistics.median(samples)
            p90 = Percentile(samples, 0.90)
            detail = f"latency_stats p50_ms={p50:.1f} p90_ms={p90:.1f} hz={tickHz:.0f}"
            await emit_event("bridge_health", {"level": "info", "detail": detail})
            lastStatsTs = time.time()

