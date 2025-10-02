from __future__ import annotations
import asyncio, time, statistics, sys, pathlib as _pathlib
from typing import Any
from jsonschema import validate, ValidationError
from collections import deque

SRC = _pathlib.Path(__file__).resolve().parents[1] # ai/src
if str(SRC) not in sys.path:
    sys.path.append(str(SRC))

from actions.codec import ClampAction
from policy.dummy import decide

# Safe implementation of adding items to a queue
async def QueueAdd(q: asyncio.Queue, item: Any, drop_policy: str, on_drop):
    try:
        q.put_nowait(item)
    except asyncio.QueueFull:
        if drop_policy == "oldest":
            q.get_nowait()  
            
            # on_drop polict might be implemented later
            if on_drop: await on_drop("oldest")
            q.put_nowait(item)
        elif drop_policy == "newest":
            if on_drop: await on_drop("newest")
        else:
            await q.put(item)

def _idle_payload():
    return {
        "look": {"dYaw": 0.0, "dPitch": 0.0},
        "move": {"forward": 0.0, "strafe": 0.0},
        "jump": False
    }

def _percentile(sorted_vals, p):
    # p in [0,1]; input should be pre-sorted
    if not sorted_vals:
        return 0.0
    k = max(0, min(len(sorted_vals) - 1, int(round(p * (len(sorted_vals) - 1)))))
    return sorted_vals[k]

async def PolicyWorker(
    obs_q: asyncio.Queue,
    act_q: asyncio.Queue,
    drop_policy: str,
    act_schema: dict,
    on_drop,
    log,
    emit_event=None,   # <-- NEW: async callable kind,payload -> None (optional)
):
    """
    Runs at 10 Hz. Each tick:
      - drains obs_q and keeps only the most recent observation
      - runs decide(obs) with a 100 ms budget
      - clamps, validates, and enqueues the action
      - tracks latency and emits latency_stats ~every 2 s (if emit_event provided)
    """
    seq_out = 0
    tick_hz = 10.0
    tick_dt = 1.0 / tick_hz
    next_tick = time.time()

    latest_obs = None
    lat_samples_ms = deque(maxlen=200)  # ~20 s of samples @10 Hz
    last_stats_ts = time.time()
    loop = asyncio.get_running_loop()

    async def _drain_latest():
        nonlocal latest_obs
        drained = False
        while True:
            try:
                item = obs_q.get_nowait()
            except asyncio.QueueEmpty:
                break
            else:
                latest_obs = item
                obs_q.task_done()
                drained = True
        return drained

    while True:
        # pace to 10 Hz
        now = time.time()
        if now < next_tick:
            await asyncio.sleep(next_tick - now)
        next_tick += tick_dt

        # keep only the most recent observation
        await _drain_latest()

        # decide with 100 ms budget
        if latest_obs is None:
            payload = _idle_payload()
        else:
            obs_ts = float(latest_obs.get("timestamp", time.time()))
            try:
                decision = await asyncio.wait_for(
                    loop.run_in_executor(None, decide, latest_obs),
                    timeout=0.100
                )
                payload = decision
            except asyncio.TimeoutError:
                log.warning("decide() timed out; sending idle")
                payload = _idle_payload()
                if emit_event:
                    await emit_event("policy_error", {"error": "decide_timeout"})
            except Exception as e:
                log.warning("decide() error; sending idle", extra={"error": str(e)})
                payload = _idle_payload()
                if emit_event:
                    await emit_event("policy_error", {"error": str(e)})

            # track e2e latency from obs timestamp (seconds → ms)
            lat_ms = max(0.0, (time.time() - obs_ts) * 1000.0)
            lat_samples_ms.append(lat_ms)

        # build action message
        msg = {
            "type": "action",
            "timestamp": time.time(),       # seconds
            "seq": seq_out,
            "schema_version": "v0",
            "payload": payload
        }

        # clamp + validate; fallback to idle if invalid
        msg = ClampAction(msg)
        try:
            validate(instance=msg, schema=act_schema)
        except ValidationError as e:
            log.warning("outgoing action failed schema; using idle", extra={"error": str(e)})
            msg["payload"] = _idle_payload()

        await QueueAdd(act_q, msg, drop_policy, on_drop)
        seq_out += 1

        # emit latency_stats ~every 2 s
        if emit_event and (time.time() - last_stats_ts >= 2.0) and len(lat_samples_ms) >= 5:
            samples = sorted(lat_samples_ms)
            p50 = statistics.median(samples)
            p90 = _percentile(samples, 0.90)
            await emit_event("latency_stats", {"p50_ms": p50, "p90_ms": p90, "hz": tick_hz})
            last_stats_ts = time.time()
