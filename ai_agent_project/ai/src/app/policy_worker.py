from __future__ import annotations
import asyncio, time, statistics, sys
import pathlib as _pathlib
from typing import Any, Optional
import uuid
from jsonschema import validate, ValidationError
from collections import deque
import logging as stdlog

log = stdlog.getLogger("bridge.server")
# Make sure ai/src is on path if needed
SRC = _pathlib.Path(__file__).resolve().parents[1]  # ai/src
if str(SRC) not in sys.path:
    sys.path.append(str(SRC))


async def QueueAdd(
    q: asyncio.Queue, item: Any, put_timeout_s: Optional[float], log, emit_event=None
) -> None:
    if not put_timeout_s or put_timeout_s <= 0:
        await q.put(item)
        return
    try:
        await asyncio.wait_for(q.put(item), timeout=put_timeout_s)
    except asyncio.TimeoutError:
        log.warning("act_q put timeout", extra={"qsize": q.qsize()})
        if emit_event:
            await emit_event(
                "bridge_health", {"level": "warn", "detail": "act_q_put_timeout"}
            )
        # still block to preserve "never drop"
        await q.put(item)


def Percentile(sortedVals, p: float) -> float:
    if not sortedVals:
        return 0.0
    k = max(0, min(len(sortedVals) - 1, int(round(p * (len(sortedVals) - 1)))))
    return float(sortedVals[k])


def normalize_action(
    action_msg: dict, default_deadline_ms: int, seq_hint: Optional[int] = None
) -> dict:
    action_msg = dict(action_msg)  # shallow copy

    action_msg.setdefault("proto", "1")
    action_msg.setdefault("kind", "action")
    action_msg.setdefault("timestamp", time.time())

    # Ensure payload exists
    payload = action_msg.get("payload")
    if not isinstance(payload, dict):
        payload = {}
        action_msg["payload"] = payload

    # Ensure deadline_ms exists (top-level)
    if "deadline_ms" not in action_msg:
        action_msg["deadline_ms"] = int(default_deadline_ms)
    else:
        action_msg["deadline_ms"] = int(action_msg["deadline_ms"])

    # Force top-level action_id
    if not action_msg.get("action_id"):
        action_msg["action_id"] = f"p_{uuid.uuid4().hex[:10]}"

    # Optional: seq hint if caller wants to set it here
    if seq_hint is not None and "seq" not in action_msg:
        action_msg["seq"] = int(seq_hint)

    # Now that the envelope is normalized, compute whether this action needs an ack
    log.debug(
        "policy_payload_keys",
        extra={
            "keys": list(payload.keys()),
            "await_result": action_needs_ack(action_msg),
        },
    )

    return action_msg


DISCRETE_KEYS = {
    "attack",
    "use",
    "select_slot",
}  # add more later (place_block, break_block, etc.)


def action_needs_ack(action_msg: dict) -> bool:
    action_id = str(action_msg.get("action_id") or "").strip()
    if action_id in DISCRETE_KEYS:
        return True
    payload = action_msg.get("payload") or {}
    return any(k in payload for k in DISCRETE_KEYS)


async def PolicyWorker(
    obs_q: asyncio.Queue,
    act_q: asyncio.Queue,
    runtime_cfg: dict,
    queues_cfg: dict,  # NEW: unified runtime config
    act_schema: dict,
    log,
    emit_event=None,
    policy_step=None,
    drop_policy: str = "block",  # keep for compat but don’t use
    on_drop=None,  # keep for compat
):
    """
    Main RL loop:

    - Pull most recent observation message from obs_q
    - Call policy_step(obsMsg) → action message dict
    - Validate action JSON (matches action.schema.json)
    - Enqueue for server → Minecraft
    - Emit latency stats every ~2 seconds
    """
    tick_hz = float(runtime_cfg.get("policy", {}).get("tick_hz", 20.0))
    tick_hz = max(1.0, tick_hz)
    tick_dt = 1.0 / tick_hz
    next_tick = time.time()

    RESEND_INTERVAL_S = float(runtime_cfg.get("continuous_resend_interval_s", 0.05))
    RESEND_INTERVAL_S = max(0.0, RESEND_INTERVAL_S)

    coalesce_cfg = (
        (queues_cfg.get("coalesce") or {})
        if isinstance(queues_cfg.get("coalesce"), dict)
        else {}
    )
    coalesce_enabled = bool(coalesce_cfg.get("enabled", True))
    coalesce_kinds = set(coalesce_cfg.get("kinds", ["look", "move"]))

    def is_control_action(msg: dict) -> bool:
        p = msg.get("payload") or {}
        return any(k in p for k in coalesce_kinds)

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
                drained = True
        return drained

    last_payload = None
    last_send_ts = 0.0

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

            raw_payload = (action_msg or {}).get("payload") or {}
            log.debug(
                "policy_raw",
                extra={
                    "payload_keys": list(raw_payload.keys()),
                    "action_id": (action_msg or {}).get("action_id"),
                },
            )

            action_msg = normalize_action(
                action_msg, int(runtime_cfg.get("default_deadline_ms", 50))
            )

            needs_ack = action_needs_ack(action_msg)
            action_msg["await_result"] = needs_ack

            norm_payload = (action_msg or {}).get("payload") or {}
            log.debug(
                "policy_norm",
                extra={
                    "payload_keys": list(norm_payload.keys()),
                    "action_id": (action_msg or {}).get("action_id"),
                    "await_result": bool(action_msg.get("await_result", False)),
                },
            )

            if runtime_cfg.get("validate_actions", True):
                try:
                    validate(instance=action_msg, schema=act_schema)
                except ValidationError as ve:
                    log.warning(
                        "action failed schema",
                        extra={"error": ve.message, "path": list(ve.path)},
                    )
                    if emit_event:
                        await emit_event(
                            "bridge_health",
                            {
                                "level": "warn",
                                "detail": f"action_schema_error:{ve.message}",
                            },
                        )
                    continue

            payload = action_msg.get("payload") or {}

            if not needs_ack:
                now2 = time.time()
                if (
                    payload == last_payload
                    and (now2 - last_send_ts) < RESEND_INTERVAL_S
                ):
                    log.debug(
                        "suppress_duplicate_control",
                        extra={
                            "age_s": (now2 - last_send_ts),
                            "resend_s": RESEND_INTERVAL_S,
                            "payload_keys": list((payload or {}).keys()),
                        },
                    )
                    continue
                last_payload = payload
                last_send_ts = now2

            # 3) Coalesce controls ONLY when the queue is actually backing up.
            # Keep the newest control, preserve ACK actions.
            if (
                coalesce_enabled
                and (not action_msg["await_result"])
                and is_control_action(action_msg)
            ):
                # Only coalesce if queue is above a threshold (prevents dropping in steady-state)
                threshold = int(coalesce_cfg.get("threshold", 10))
                if act_q.qsize() >= threshold:
                    kept = []
                    newest_control = None
                    dropped = 0

                    try:
                        while True:
                            it = act_q.get_nowait()
                            # Anything that isn't a droppable control is preserved
                            if not (
                                isinstance(it, dict)
                                and (not it.get("await_result", False))
                                and is_control_action(it)
                            ):
                                kept.append(it)
                            else:
                                newest_control = it  # keep overwriting; last one wins
                                dropped += 1
                            act_q.task_done()
                    except asyncio.QueueEmpty:
                        pass

                    # Requeue preserved items in order
                    for it in kept:
                        await act_q.put(it)

                    # Requeue the newest control (if we dropped any)
                    if newest_control is not None:
                        await act_q.put(newest_control)

                    if dropped and emit_event:
                        await emit_event(
                            "bridge_health",
                            {
                                "level": "info",
                                "detail": f"coalesced_controls dropped={dropped}",
                            },
                        )

            put_timeout_s = float(queues_cfg.get("act_put_timeout_s", 0) or 0)
            log.debug(
                "policy_payload",
                extra={"keys": list(payload.keys()), "payload": payload},
            )
            await QueueAdd(act_q, action_msg, put_timeout_s, log, emit_event)

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
