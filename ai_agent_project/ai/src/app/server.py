# server.py  (Sprint-2 complete through Step 3, updated for DQN + seq + simplified action sending)
# Added:
#   - Auto respawn
#   - Episode start/end events
#   - Episode persistence (resume episode count across restarts)
#   - Episode timeout (full Minecraft day = 20 minutes)
#   - FIXED shared directory path to be robust with your real file tree

import asyncio
import json
import os
import sys
import time
import pathlib
import logging as stdlog
import contextlib
from typing import Any, Dict, Optional

from websockets.server import serve, WebSocketServerProtocol
from websockets.exceptions import (
    ConnectionClosed,
    ConnectionClosedOK,
    ConnectionClosedError,
)
from jsonschema import validate, ValidationError

# from ai/src/app to ai
sys.path.append(str(pathlib.Path(__file__).resolve().parents[3]))
from ai.src.utils.config import LoadConfig
from ai.src.utils.logging import SetupLogging, WriteMetric

# DQN policy registry
from ai.src.policy.registry import build_policy_from_config
from ai.src.app.policy_worker import PolicyWorker


# ============================================================
#  FAST JSON
# ============================================================

try:
    import orjson as _fastjson

    def _dumps(obj):
        return _fastjson.dumps(obj)

    def _loads(s):
        return _fastjson.loads(s if isinstance(s, (bytes, bytearray)) else s.encode("utf-8"))

    _SEND_TEXT = False
except Exception:
    import json as _fastjson

    def _dumps(obj):
        return _fastjson.dumps(obj, ensure_ascii=False, separators=(",", ":"))

    def _loads(s):
        return _fastjson.loads(s)

    _SEND_TEXT = True


# ============================================================
#  FIXED FILE TREE PATHS (correct for your exact structure)
# ============================================================

# server.py is located at:
#   ai_agent_project/ai/src/app/server.py
# project_root = parents[3] = ai_agent_project/
server_root = pathlib.Path(__file__).resolve()
project_root = server_root.parents[3]  # ai_agent_project directory

sharedDir = project_root / "shared"
schemasDir = sharedDir / "schemas"

OBS = json.loads((schemasDir / "observation.schema.json").read_text("utf-8"))
ACT = json.loads((schemasDir / "action.schema.json").read_text("utf-8"))
EVT = json.loads((schemasDir / "event.schema.json").read_text("utf-8"))


# ============================================================
#  EPISODE PERSISTENCE + AUTO RESPAWN SUPPORT
# ============================================================

EPISODE_SAVE_PATH = sharedDir / "episode_state.json"
episode = 0
episode_start_time = None
MC_DAY_SECONDS = 1200  # 20 min


def LoadEpisodeNumber():
    global episode
    try:
        if EPISODE_SAVE_PATH.exists():
            data = json.loads(EPISODE_SAVE_PATH.read_text())
            episode = int(data.get("episode", 0))
            stdlog.getLogger("bridge.server").info(f"Loaded episode {episode} from previous run")
    except Exception as e:
        stdlog.getLogger("bridge.server").warning(
            "Failed to load episode persistence", extra={"error": str(e)}
        )


def SaveEpisodeNumber():
    try:
        EPISODE_SAVE_PATH.write_text(json.dumps({"episode": episode}))
    except Exception as e:
        stdlog.getLogger("bridge.server").warning(
            "Failed to save episode number", extra={"error": str(e)}
        )


async def start_new_episode(ws):
    global episode, episode_start_time
    episode += 1
    episode_start_time = time.time()
    SaveEpisodeNumber()

    stdlog.getLogger("bridge.server").info(f"=== Starting Episode {episode} ===")

    await SendCommand(ws, "respawn")
    await asyncio.sleep(1.0)

    await SendEvents(ws, "episode_start", {"episode": episode})


# ============================================================
#  UTILITIES
# ============================================================

async def SendEvents(ws: WebSocketServerProtocol, kind: str, payload: dict) -> None:
    log = stdlog.getLogger("bridge.server.SendEvents")
    msg = {
        "proto": "1",
        "kind": kind,
        "seq": 0,
        "timestamp": time.time(),
        "payload": {kind: payload},
    }
    try:
        validate(instance=msg, schema=EVT)
    except ValidationError as e:
        log.warning("internal event failed schema", extra={"error": str(e), "kind": kind})
    await ws.send(_dumps(msg))


async def SendCommand(ws: WebSocketServerProtocol, cmd: str) -> None:
    msg = {
        "proto": "1",
        "kind": "command",
        "seq": int(time.time() * 1000),
        "timestamp": time.time(),
        "payload": {"cmd": cmd},
    }
    await ws.send(_dumps(msg))
    stdlog.getLogger("bridge.server").info("sent command", extra={"cmd": cmd})


async def EnqueueObservation(q, item, state):
    try:
        q.put_nowait(item)
    except asyncio.QueueFull:
        try:
            _ = q.get_nowait()
            state["obsDropped"] = state.get("obsDropped", 0) + 1
            q.put_nowait(item)
        except Exception:
            state["obsDropped"] = state.get("obsDropped", 0) + 1
    state["obsHighWatermark"] = max(state.get("obsHighWatermark", 0), q.qsize())


async def MetricsLoop(stopEvt, cfg, obsState, obsQ, actState, actQ):
    log = stdlog.getLogger("bridge.server.MetricsLoop")
    metricsCfg = cfg.get("bridge", {}).get("metrics", {})
    if not metricsCfg.get("enabled", True):
        return

    sinkPath = metricsCfg.get("sink", {}).get("path")
    interval = metricsCfg.get("sample_interval_s", 2)
    if not sinkPath:
        return

    while not stopEvt.is_set():
        try:
            WriteMetric(
                sinkPath,
                {
                    "queue_obs_size": obsQ.qsize(),
                    "queue_obs_high_watermark": obsState.get("obsHighWatermark", 0),
                    "obs_dropped": obsState.get("obsDropped", 0),
                    "queue_act_size": actQ.qsize(),
                    "queue_act_high_watermark": actState.get("actHighWatermark", 0),
                    "action_timeouts": actState.get("actionTimeouts", 0),
                },
            )
        except Exception as e:
            log.warning("metrics write failed", extra={"error": str(e)})
        await asyncio.sleep(interval)


async def HeartBeatLoop(ws, stopEvt):
    try:
        await SendEvents(ws, "bridge_health", {"level": "info", "detail": "connected"})
        while not stopEvt.is_set():
            await asyncio.sleep(2.0)
            await SendEvents(ws, "bridge_health", {"level": "info", "detail": "alive"})
    except:
        pass


# ============================================================
#  MAIN CONNECTION HANDLER
# ============================================================

async def Handle(ws: WebSocketServerProtocol) -> None:
    log = stdlog.getLogger("bridge.server")
    cfg = LoadConfig(env=os.getenv("APP_ENV", "dev"))

    SetupLogging(cfg.bridge.get("logging", {}))

    queuesCfg = cfg.bridge.get("queues", {})
    obsQueue = asyncio.Queue(maxsize=queuesCfg.get("obs_max", 128))
    obsState = {"obsDropped": 0, "obsHighWatermark": 0}

    actQueue = asyncio.Queue(maxsize=queuesCfg.get("act_max", 64))
    actState = {"actHighWatermark": 0, "actionTimeouts": 0}
    pending = {}

    stopEvt = asyncio.Event()
    tasks = []

    # Load policy (DQN)
    runtime_cfg = getattr(cfg, "runtime", {})
    policy = build_policy_from_config(runtime_cfg)

    async def emit_event(kind, payload):
        await SendEvents(ws, kind, payload)

    seqCounter = 0

    async def SendAction(actionMsg, timeoutMs=300, wait_for_result=True):
        nonlocal seqCounter
        seqCounter += 1

        actionMsg["seq"] = seqCounter
        actionMsg.setdefault("proto", "1")
        actionMsg.setdefault("kind", "action")
        actionMsg.setdefault("timestamp", time.time())

        if not wait_for_result:
            await ws.send(json.dumps(actionMsg))
            return None

        validate(instance=actionMsg, schema=ACT)

        actionId = actionMsg.get("action_id") or actionMsg.get("payload", {}).get("action_id")
        if not actionId:
            raise ValueError("action_id missing")

        fut = asyncio.get_running_loop().create_future()
        pending[actionId] = fut

        await ws.send(json.dumps(actionMsg))

        try:
            return await asyncio.wait_for(fut, timeoutMs / 1000)
        except asyncio.TimeoutError:
            actState["actionTimeouts"] += 1
            pending.pop(actionId, None)
            raise

    # Scheduler
    def _exact_scheduler(hz):
        dt = 1.0 / float(max(1, hz))
        next_deadline = time.perf_counter()

        async def sleep_exact():
            nonlocal next_deadline
            next_deadline += dt
            delay = next_deadline - time.perf_counter()
            if delay > 0:
                await asyncio.sleep(delay)
            else:
                next_deadline = time.perf_counter()

        return sleep_exact

    async def ActionSenderLoop(stopEvt):
        hz = max(20, int(cfg.runtime.get("policy", {}).get("tick_hz", 20)))
        sleep_exact = _exact_scheduler(hz)

        while not stopEvt.is_set():
            maxPerTick = int(cfg.runtime.get("policy", {}).get("max_actions_per_tick", 2))
            sent = 0

            while sent < maxPerTick:
                try:
                    item = actQueue.get_nowait()
                except asyncio.QueueEmpty:
                    break

                try:
                    await SendAction(item)
                except Exception as e:
                    log.warning("action send failed", extra={"error": str(e)})

                sent += 1

            await sleep_exact()

    # Background tasks
    tasks.append(asyncio.create_task(MetricsLoop(stopEvt, cfg, obsState, obsQueue, actState, actQueue)))
    tasks.append(asyncio.create_task(HeartBeatLoop(ws, stopEvt)))
    tasks.append(asyncio.create_task(ActionSenderLoop(stopEvt)))

    # RL Worker
    tasks.append(
        asyncio.create_task(
            PolicyWorker(
                obs_q=obsQueue,
                act_q=actQueue,
                drop_policy="block",
                act_schema=ACT,
                on_drop=None,
                log=stdlog.getLogger("bridge.policy"),
                emit_event=emit_event,
                policy_step=policy.act,
            )
        )
    )

    # Start first episode
    await start_new_episode(ws)

    # ============================================================
    #  RECEIVE LOOP
    # ============================================================

    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except:
                await SendEvents(ws, "bridge_health", {"level": "warn", "detail": "invalid_json"})
                continue

            if msg.get("proto") != "1":
                continue

            kind = msg.get("kind")

            # -------- Observation --------
            if kind == "observation":
                try:
                    validate(instance=msg, schema=OBS)
                except ValidationError:
                    continue

                await EnqueueObservation(obsQueue, msg, obsState)

                observation = msg["payload"]["observation"]

                # ============== EPISODE LOGIC ==============

                # 1. Death → end + start new
                if observation.get("dead", False):
                    await SendEvents(ws, "episode_end", {"episode": episode, "reason": "death"})
                    await start_new_episode(ws)
                    continue

                # 2. Minecraft day timer
                if episode_start_time is not None:
                    elapsed = time.time() - episode_start_time
                    if elapsed >= MC_DAY_SECONDS:
                        await SendEvents(ws, "episode_end", {"episode": episode, "reason": "day_complete"})
                        await start_new_episode(ws)
                        continue

            # -------- Action results --------
            elif kind == "action_result":
                try:
                    validate(instance=msg, schema=EVT)
                except ValidationError:
                    continue

                res = msg["payload"]["action_result"]
                actionId = res["action_id"]

                fut = pending.pop(actionId, None)
                if fut and not fut.done():
                    fut.set_result(res)

            # -------- Health --------
            elif kind == "bridge_health":
                try:
                    validate(instance=msg, schema=EVT)
                except ValidationError:
                    continue

            # -------- Episode events (ignored) --------
            elif kind in ("episode_start", "episode_end"):
                continue

    except (ConnectionClosed, ConnectionClosedOK, ConnectionClosedError):
        log.info("client disconnected")

    finally:
        stopEvt.set()
        for t in tasks:
            t.cancel()
        with contextlib.suppress(Exception):
            await asyncio.gather(*tasks, return_exceptions=True)


# ============================================================
#  ENTRYPOINT
# ============================================================

async def Main():
    LoadEpisodeNumber()

    cfg = LoadConfig(env=os.getenv("APP_ENV", "dev"))
    SetupLogging(cfg.bridge.get("logging", {}))

    serverCfg = cfg.bridge["server"]
    log = stdlog.getLogger("bridge.server")
    log.info("starting server", extra={"host": serverCfg["host"], "port": serverCfg["port"]})

    async with serve(
        Handle,
        serverCfg["host"],
        serverCfg["port"],
        ping_interval=serverCfg.get("ping_interval_s", 5),
        ping_timeout=serverCfg.get("ping_timeout_s", 5),
        max_size=serverCfg.get("max_msg_bytes", 1048576),
    ):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(Main())
