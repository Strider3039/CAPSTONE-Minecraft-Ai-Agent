#!/usr/bin/env python3
"""
Experimental data for conference: baseline, parameter sensitivity, and stability runs.

- **Baseline**: Default config, 20+ episodes; record success rate, mean episode length,
  mean p50/p90 latency, disconnects. Output: reports/baseline_run.json
- **Parameter sensitivity**: 3–5 runs each with one changed parameter (tick_hz 10 vs 20,
  step_penalty -0.001 vs -0.01, epsilon_end 0.1 vs 0.5, obs rate_hz 6 vs 12).
  Output: reports/parameter_sensitivity.csv
- **Stability**: Long run (100+ episodes or 1–2 hours); record latency and disconnects
  over time to show no memory growth or latency drift. Output: reports/stability_run.json

Usage:
  PYTHONPATH=. python -m scripts.run_experiments baseline [--episodes 25] [--start-bridge]
  PYTHONPATH=. python -m scripts.run_experiments sensitivity [--runs-per-value 3] [--start-bridge]
  PYTHONPATH=. python -m scripts.run_experiments stability [--max-episodes 100] [--max-hours 2] [--start-bridge]
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import json
import os
import subprocess
import sys
import time
from pathlib import Path

_SCRIPT_DIR = Path(__file__).resolve().parent
_PROJECT_ROOT = _SCRIPT_DIR.parent
_SHARED_DIR = _PROJECT_ROOT / "shared"
_DATA_DIR = _SHARED_DIR / "Data"
_CONFIG_DIR = _SHARED_DIR / "config"
_REPORTS_DIR = _PROJECT_ROOT / "reports"

if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

import yaml

try:
    import websockets
except ImportError:
    websockets = None

# Reuse eval runner helpers
from scripts.run_eval import (
    make_mock_observation,
    read_episode_history_last,
    read_bridge_metrics_in_window,
    aggregate_metrics,
    load_yaml,
    save_yaml,
)

# Default episode cap and timeout (align with typical reward engine)
DEFAULT_MAX_STEPS = 2000
DEFAULT_EPISODE_TIMEOUT_S = 120.0
DEFAULT_TICK_HZ = 20.0


def mean_latency_from_metrics(rows: list[dict]) -> tuple[float | None, float | None]:
    """From NDJSON metric rows that have tick_latency_p50_ms / p90_ms, return (mean_p50, mean_p90)."""
    p50s = [r["tick_latency_p50_ms"] for r in rows if r.get("tick_latency_p50_ms") is not None]
    p90s = [r["tick_latency_p90_ms"] for r in rows if r.get("tick_latency_p90_ms") is not None]
    mean_p50 = sum(p50s) / len(p50s) if p50s else None
    mean_p90 = sum(p90s) / len(p90s) if p90s else None
    return (mean_p50, mean_p90)


async def run_one_episode(
    ws,
    episode_index: int,
    max_steps: int,
    episode_timeout_s: float,
    tick_hz: float,
    episode_history_path: Path,
    bridge_metrics_path: Path,
    seed: int = 42,
) -> tuple[dict, float, float]:
    """
    Run a single episode: start_episode if index>0, send observations, return
    (episode_row, t_start, t_end) for metrics window.
    """
    if episode_index > 0:
        await ws.send(json.dumps({
            "proto": "1",
            "kind": "eval_control",
            "seq": episode_index,
            "timestamp": time.time(),
            "payload": {"action": "start_episode"},
        }))
        await asyncio.sleep(0.2)

    t_start = time.time()
    seq = 0
    step = 0
    dt = 1.0 / tick_hz if tick_hz > 0 else 0.05

    while step < max_steps:
        if (time.time() - t_start) >= episode_timeout_s:
            break
        obs = make_mock_observation(seq, step, seed)
        await ws.send(json.dumps(obs))
        seq += 1
        step += 1
        await asyncio.sleep(dt)

    t_end = time.time()
    await asyncio.sleep(0.5)

    entry = read_episode_history_last(episode_history_path)
    steps = entry.get("episode_step") or entry.get("steps") or step if entry else step
    duration_s = entry.get("duration_seconds") if entry else (t_end - t_start)
    reason = (entry or {}).get("done_reason") or (entry or {}).get("reason")
    success = 1 if reason == "reward_engine_done" else 0

    row = {
        "episode": episode_index + 1,
        "success": success,
        "steps": steps,
        "episode_duration_s": round(duration_s, 2) if duration_s is not None else round(t_end - t_start, 2),
    }
    return row, t_start, t_end


async def run_n_episodes(
    ws,
    n_episodes: int,
    max_steps: int,
    episode_timeout_s: float,
    tick_hz: float,
    config_overrides: dict,
    episode_history_path: Path,
    bridge_metrics_path: Path,
) -> tuple[list[dict], dict]:
    """
    Apply config_overrides, run n_episodes, return (list of per-episode rows, aggregated stats).
    """
    if config_overrides:
        await ws.send(json.dumps({
            "proto": "1",
            "kind": "config_update",
            "seq": 0,
            "timestamp": time.time(),
            "payload": config_overrides,
        }))
        await asyncio.sleep(0.3)

    # Also set max_steps for reward engine so episodes end at max_steps
    await ws.send(json.dumps({
        "proto": "1",
        "kind": "config_update",
        "seq": 0,
        "timestamp": time.time(),
        "payload": {
            "policy": {"reward": {"max_steps_per_episode": max_steps}},
        },
    }))
    await asyncio.sleep(0.2)

    rows = []
    t_first = time.time()
    t_last = time.time()

    for ep in range(n_episodes):
        try:
            row, t_start, t_end = await run_one_episode(
                ws, ep, max_steps, episode_timeout_s, tick_hz,
                episode_history_path, bridge_metrics_path, seed=42 + ep,
            )
            rows.append(row)
            if ep == 0:
                t_first = t_start
            t_last = t_end
        except Exception as e:
            rows.append({"episode": ep + 1, "success": 0, "steps": 0, "episode_duration_s": 0})
            print(f"  Episode {ep + 1} failed: {e}", file=sys.stderr)

    metrics_rows = read_bridge_metrics_in_window(bridge_metrics_path, t_first, t_last)
    agg = aggregate_metrics(metrics_rows)
    mean_p50, mean_p90 = mean_latency_from_metrics(metrics_rows)

    n = len(rows)
    successes = [r["success"] for r in rows]
    steps_list = [r["steps"] for r in rows if r.get("steps") is not None]
    durations = [r["episode_duration_s"] for r in rows if r.get("episode_duration_s") is not None]

    return rows, {
        "success_rate": sum(successes) / n if n else 0,
        "mean_episode_length": sum(steps_list) / len(steps_list) if steps_list else 0,
        "mean_episode_duration_s": sum(durations) / len(durations) if durations else 0,
        "mean_p50_ms": round(mean_p50, 2) if mean_p50 is not None else None,
        "mean_p90_ms": round(mean_p90, 2) if mean_p90 is not None else None,
        "disconnects": agg["reconnects"],
        "queue_obs_high_watermark_max": agg["queue_obs_high_watermark"],
        "action_timeouts": agg["action_timeouts"],
        "n_episodes": n,
    }


async def send_hello(ws) -> None:
    """Send hello as client with PLAYER so server accepts."""
    await ws.send(json.dumps({
        "proto": "1",
        "kind": "hello",
        "seq": 0,
        "timestamp": time.time(),
        "role": "client",
        "control_mode": "PLAYER",
    }))
    await asyncio.sleep(0.3)


def ensure_bridge_ready(host: str, port: int, overlay_path: Path, start_bridge: bool):
    """If start_bridge, write PLAYER overlay and start server subprocess; return process or None."""
    if not start_bridge:
        return None
    _DATA_DIR.mkdir(parents=True, exist_ok=True)
    save_yaml(overlay_path, {"control_mode": "PLAYER"})
    proc = subprocess.Popen(
        [sys.executable, "-m", "ai.src.app.server"],
        cwd=str(_PROJECT_ROOT),
        env={**os.environ, "PYTHONPATH": str(_PROJECT_ROOT), "APP_ENV": "prod"},
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    for _ in range(50):
        time.sleep(0.2)
        try:
            import socket
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.5)
            s.connect((host, port))
            s.close()
            break
        except Exception:
            continue
    else:
        proc.terminate()
        raise RuntimeError("Bridge did not become ready in time")
    return proc


# Parameter sensitivity: (param_key, list of values) for config_update payload.
SENSITIVITY_PARAMS = [
    ("policy.tick_hz", [10, 20]),
    ("policy.reward.step_penalty", [-0.001, -0.01]),
    ("policy.dqn.epsilon_end", [0.1, 0.5]),
    ("obs.rate_hz", [6, 12]),
]


def config_overrides_for_param(param_key: str, value) -> dict:
    """Build config_update payload for a single parameter."""
    if param_key == "policy.tick_hz":
        return {"policy": {"tick_hz": value}}
    if param_key == "policy.reward.step_penalty":
        return {"policy": {"reward": {"step_penalty": value}}}
    if param_key == "policy.dqn.epsilon_end":
        return {"policy": {"dqn": {"epsilon_end": value}}}
    if param_key == "obs.rate_hz":
        return {"obs": {"rate_hz": value}}
    return {}


def tick_hz_for_param(param_key: str, value) -> float:
    """Observation send rate: for obs.rate_hz use value, else default."""
    if param_key == "obs.rate_hz":
        return float(value)
    return DEFAULT_TICK_HZ


async def cmd_baseline(
    host: str,
    port: int,
    start_bridge: bool,
    episodes: int,
    bridge_metrics_path: Path,
    episode_history_path: Path,
    overlay_path: Path,
    reports_dir: Path,
) -> int:
    """Baseline run: default config, 20+ episodes; record success rate, mean length, mean p50/p90, disconnects."""
    proc = ensure_bridge_ready(host, port, overlay_path, start_bridge)
    try:
        async with websockets.connect(f"ws://{host}:{port}") as ws:
            await send_hello(ws)
            rows, stats = await run_n_episodes(
                ws,
                episodes,
                DEFAULT_MAX_STEPS,
                DEFAULT_EPISODE_TIMEOUT_S,
                DEFAULT_TICK_HZ,
                {},  # default config
                episode_history_path,
                bridge_metrics_path,
            )
    finally:
        if proc:
            proc.terminate()

    out = {
        "experiment": "baseline",
        "config": "default",
        "n_episodes": stats["n_episodes"],
        "success_rate": stats["success_rate"],
        "mean_episode_length": stats["mean_episode_length"],
        "mean_episode_duration_s": stats["mean_episode_duration_s"],
        "mean_p50_ms": stats["mean_p50_ms"],
        "mean_p90_ms": stats["mean_p90_ms"],
        "disconnects": stats["disconnects"],
        "per_episode": rows,
    }
    reports_dir.mkdir(parents=True, exist_ok=True)
    path = reports_dir / "baseline_run.json"
    path.write_text(json.dumps(out, indent=2), encoding="utf-8")
    print(f"Baseline: success_rate={stats['success_rate']:.2%} mean_steps={stats['mean_episode_length']:.0f} mean_p50_ms={stats['mean_p50_ms']} disconnects={stats['disconnects']}")
    print(f"Wrote {path}")
    return 0


async def cmd_sensitivity(
    host: str,
    port: int,
    start_bridge: bool,
    runs_per_value: int,
    episodes_per_run: int,
    bridge_metrics_path: Path,
    episode_history_path: Path,
    overlay_path: Path,
    reports_dir: Path,
) -> int:
    """Parameter sensitivity: 3–5 runs each with one changed parameter; record same metrics."""
    proc = ensure_bridge_ready(host, port, overlay_path, start_bridge)
    all_rows: list[dict] = []
    try:
        async with websockets.connect(f"ws://{host}:{port}") as ws:
            await send_hello(ws)

            for param_key, values in SENSITIVITY_PARAMS:
                for value in values:
                    overrides = config_overrides_for_param(param_key, value)
                    tick_hz = tick_hz_for_param(param_key, value)
                    for run_id in range(1, runs_per_value + 1):
                        try:
                            rows, stats = await run_n_episodes(
                                ws,
                                episodes_per_run,
                                DEFAULT_MAX_STEPS,
                                DEFAULT_EPISODE_TIMEOUT_S,
                                tick_hz,
                                overrides,
                                episode_history_path,
                                bridge_metrics_path,
                            )
                            all_rows.append({
                                "parameter": param_key,
                                "value": value,
                                "run_id": run_id,
                                "success_rate": stats["success_rate"],
                                "mean_episode_length": stats["mean_episode_length"],
                                "mean_p50_ms": stats["mean_p50_ms"] if stats["mean_p50_ms"] is not None else "",
                                "mean_p90_ms": stats["mean_p90_ms"] if stats["mean_p90_ms"] is not None else "",
                                "disconnects": stats["disconnects"],
                            })
                            print(f"  {param_key}={value} run {run_id}: success_rate={stats['success_rate']:.2%} mean_p50_ms={stats['mean_p50_ms']}")
                        except Exception as e:
                            print(f"  {param_key}={value} run {run_id} failed: {e}", file=sys.stderr)
                            all_rows.append({
                                "parameter": param_key,
                                "value": value,
                                "run_id": run_id,
                                "success_rate": 0,
                                "mean_episode_length": 0,
                                "mean_p50_ms": "",
                                "mean_p90_ms": "",
                                "disconnects": 0,
                            })
    finally:
        if proc:
            proc.terminate()

    reports_dir.mkdir(parents=True, exist_ok=True)
    path = reports_dir / "parameter_sensitivity.csv"
    columns = ["parameter", "value", "run_id", "success_rate", "mean_episode_length", "mean_p50_ms", "mean_p90_ms", "disconnects"]
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=columns, extrasaction="ignore")
        w.writeheader()
        w.writerows(all_rows)
    print(f"Wrote {path}")
    return 0


async def cmd_stability(
    host: str,
    port: int,
    start_bridge: bool,
    max_episodes: int,
    max_hours: float,
    sample_every_n: int,
    bridge_metrics_path: Path,
    episode_history_path: Path,
    overlay_path: Path,
    reports_dir: Path,
) -> int:
    """Stability run: 100+ episodes or 1–2 hours; sample latency/disconnects every N episodes."""
    proc = ensure_bridge_ready(host, port, overlay_path, start_bridge)
    max_duration_s = max_hours * 3600.0
    time_series: list[dict] = []
    t_start_total = time.time()
    total_disconnects = 0
    try:
        async with websockets.connect(f"ws://{host}:{port}") as ws:
            await send_hello(ws)

            await ws.send(json.dumps({
                "proto": "1",
                "kind": "config_update",
                "seq": 0,
                "timestamp": time.time(),
                "payload": {"policy": {"reward": {"max_steps_per_episode": DEFAULT_MAX_STEPS}}},
            }))
            await asyncio.sleep(0.2)

            ep = 0
            t_interval_start = time.time()
            while ep < max_episodes and (time.time() - t_start_total) < max_duration_s:
                try:
                    row, t_start, t_end = await run_one_episode(
                        ws, ep, DEFAULT_MAX_STEPS, DEFAULT_EPISODE_TIMEOUT_S, DEFAULT_TICK_HZ,
                        episode_history_path, bridge_metrics_path, seed=42 + ep,
                    )
                    ep += 1

                    if ep % sample_every_n == 0 or ep == 1:
                        metrics_rows = read_bridge_metrics_in_window(bridge_metrics_path, t_interval_start, t_end)
                        agg = aggregate_metrics(metrics_rows)
                        mean_p50, mean_p90 = mean_latency_from_metrics(metrics_rows)
                        total_disconnects = agg["reconnects"]
                        time_series.append({
                            "episode": ep,
                            "wall_clock_s": round(time.time() - t_start_total, 1),
                            "mean_p50_ms": round(mean_p50, 2) if mean_p50 is not None else None,
                            "mean_p90_ms": round(mean_p90, 2) if mean_p90 is not None else None,
                            "queue_obs_high_watermark": agg["queue_obs_high_watermark"],
                            "disconnects_so_far": total_disconnects,
                        })
                        print(f"  Stability sample at episode {ep}: p50={mean_p50} ms disconnects={total_disconnects}")
                    t_interval_start = t_end
                except Exception as e:
                    print(f"  Episode {ep + 1} failed: {e}", file=sys.stderr)
                    break
    finally:
        if proc:
            proc.terminate()

    total_s = time.time() - t_start_total
    out = {
        "experiment": "stability",
        "total_episodes": ep,
        "total_wall_clock_s": round(total_s, 1),
        "disconnects": total_disconnects,
        "time_series": time_series,
        "summary": "No memory growth / latency drift if mean_p50_ms and mean_p90_ms stable over time and disconnects minimal.",
    }
    reports_dir.mkdir(parents=True, exist_ok=True)
    path = reports_dir / "stability_run.json"
    path.write_text(json.dumps(out, indent=2), encoding="utf-8")
    print(f"Stability: {ep} episodes in {total_s/60:.1f} min, disconnects={total_disconnects}")
    print(f"Wrote {path}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Experimental runs: baseline, sensitivity, stability")
    parser.add_argument("mode", choices=["baseline", "sensitivity", "stability"], help="Experiment mode")
    parser.add_argument("--start-bridge", action="store_true", help="Start bridge subprocess before connecting")
    parser.add_argument("--host", default="127.0.0.1", help="Bridge host")
    parser.add_argument("--port", type=int, default=8765, help="Bridge port")
    parser.add_argument("--episodes", type=int, default=25, help="Baseline: number of episodes (default 25)")
    parser.add_argument("--runs-per-value", type=int, default=3, help="Sensitivity: runs per parameter value (default 3)")
    parser.add_argument("--episodes-per-run", type=int, default=20, help="Sensitivity: episodes per run (default 20)")
    parser.add_argument("--max-episodes", type=int, default=100, help="Stability: max episodes (default 100)")
    parser.add_argument("--max-hours", type=float, default=2.0, help="Stability: max wall-clock hours (default 2)")
    parser.add_argument("--sample-every", type=int, default=10, help="Stability: sample every N episodes (default 10)")
    parser.add_argument("--reports-dir", type=Path, default=_REPORTS_DIR, help="Output directory for reports")
    args = parser.parse_args()

    if websockets is None:
        print("Install websockets: pip install websockets", file=sys.stderr)
        return 1

    eval_cfg = load_yaml(_CONFIG_DIR / "evaluation.yaml") or {}
    metrics_cfg = eval_cfg.get("metrics", {})
    bridge_metrics_path_str = metrics_cfg.get("bridge_metrics_path", "logs/bridge_metrics.ndjson")
    bridge_metrics_path = _DATA_DIR / bridge_metrics_path_str if not Path(bridge_metrics_path_str).is_absolute() else Path(bridge_metrics_path_str)
    episode_history_path = _DATA_DIR / "episode_history.json"
    overlay_path = _DATA_DIR / "runtime_overrides.yaml"
    reports_dir = args.reports_dir

    if args.mode == "baseline":
        return asyncio.run(cmd_baseline(
            args.host, args.port, args.start_bridge, args.episodes,
            bridge_metrics_path, episode_history_path, overlay_path, reports_dir,
        ))
    if args.mode == "sensitivity":
        return asyncio.run(cmd_sensitivity(
            args.host, args.port, args.start_bridge, args.runs_per_value, args.episodes_per_run,
            bridge_metrics_path, episode_history_path, overlay_path, reports_dir,
        ))
    if args.mode == "stability":
        return asyncio.run(cmd_stability(
            args.host, args.port, args.start_bridge, args.max_episodes, args.max_hours, args.sample_every,
            bridge_metrics_path, episode_history_path, overlay_path, reports_dir,
        ))
    return 1


if __name__ == "__main__":
    sys.exit(main())
