#!/usr/bin/env python3
"""
Evaluation runner: scenario-driven runs aligned with evaluation.yaml.

- Loads evaluation.yaml and bridge config.
- For each enabled scenario, runs N trials (with seeds).
- Connects to the bridge as a client, sends hello (control_mode=PLAYER), then
  for each trial: sends eval_control start_episode (except trial 0), sends
  mock observations for up to max_steps or episode_timeout_s, then reads
  episode_history and bridge_metrics to record the row.
- Writes eval.csv and optional plots under reports/.

Usage:
  # Use existing bridge (must be running with control_mode=PLAYER or set via overlay)
  PYTHONPATH=ai_agent_project python -m scripts.run_eval

  # Start bridge automatically (writes control_mode=PLAYER to overlay, then starts server)
  PYTHONPATH=ai_agent_project python -m scripts.run_eval --start-bridge

  # Optional: limit scenarios or trials
  PYTHONPATH=ai_agent_project python -m scripts.run_eval --scenario reach_goal_basic --max-trials 5
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

# Resolve project root: script lives in ai_agent_project/scripts/
_SCRIPT_DIR = Path(__file__).resolve().parent
_PROJECT_ROOT = _SCRIPT_DIR.parent
_SHARED_DIR = _PROJECT_ROOT / "shared"
_DATA_DIR = _SHARED_DIR / "Data"
_CONFIG_DIR = _SHARED_DIR / "config"

if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

import yaml

# Optional: websockets for client
try:
    import websockets
except ImportError:
    websockets = None


def load_yaml(path: Path) -> dict:
    if not path.exists():
        return {}
    with path.open("r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def save_yaml(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        yaml.safe_dump(data, f, default_flow_style=False, allow_unicode=True, sort_keys=False)


def make_mock_observation(seq: int, step: int, seed: int) -> dict:
    """Build a minimal observation that passes observation.schema.json."""
    # Required: pose, rays, front_clear, world, inventory, collision
    return {
        "proto": "1",
        "kind": "observation",
        "seq": seq,
        "timestamp": time.time(),
        "payload": {
            "pose": {"x": 0.0, "y": 64.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0},
            "rays": [
                {"hit": False, "dist": 10.0, "angle_deg": -30 + i * 15}
                for i in range(8)
            ],
            "front_clear": True,
            "world": {"time_of_day": 6000, "weather": "clear", "biome": "plains"},
            "inventory": {"selected_slot": 0, "hotbar": [{"id": "air", "count": 0}] * 9},
            "collision": {"is_grounded": True, "is_colliding": False, "no_progress": False},
        },
    }


def read_episode_history_last(path: Path) -> dict | None:
    """Return the last episode entry from episode_history.json (list)."""
    if not path.exists():
        return None
    try:
        raw = path.read_text(encoding="utf-8")
        data = json.loads(raw)
        if isinstance(data, list) and data:
            return data[-1]
        if isinstance(data, dict):
            return data
        return None
    except Exception:
        return None


def read_bridge_metrics_in_window(path: Path, t_start: float, t_end: float) -> list[dict]:
    """Return NDJSON rows with timestamp in [t_start, t_end]."""
    if not path.exists():
        return []
    rows = []
    try:
        for line in path.read_text(encoding="utf-8").strip().split("\n"):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                ts = row.get("timestamp")
                if ts is not None and t_start <= float(ts) <= t_end:
                    rows.append(row)
            except json.JSONDecodeError:
                continue
    except Exception:
        pass
    return rows


def aggregate_metrics(rows: list[dict]) -> dict:
    """Compute max queue watermarks, sums of timeouts/drops, last connection_count."""
    out = {
        "queue_obs_high_watermark": 0,
        "queue_act_high_watermark": 0,
        "action_timeouts": 0,
        "obs_dropped": 0,
        "reconnects": 0,
        "connection_count": 0,
    }
    for r in rows:
        out["queue_obs_high_watermark"] = max(
            out["queue_obs_high_watermark"], r.get("queue_obs_high_watermark", 0)
        )
        out["queue_act_high_watermark"] = max(
            out["queue_act_high_watermark"], r.get("queue_act_high_watermark", 0)
        )
        out["action_timeouts"] += int(r.get("action_timeouts", 0))
        out["obs_dropped"] += int(r.get("obs_dropped", 0))
        if r.get("type") == "connection" and r.get("event") == "connected":
            out["reconnects"] += 1
        if "connection_count" in r:
            out["connection_count"] = r["connection_count"]
    return out


def infer_success(entry: dict, scenario: dict) -> int | None:
    """Infer success from episode entry and scenario.success.metric. Returns 0/1 or None."""
    metric = (scenario.get("success") or {}).get("metric")
    threshold = (scenario.get("success") or {}).get("threshold", 1)
    reason = entry.get("done_reason") or entry.get("reason")
    if metric == "survived":
        # Survived = finished without crash; reward_engine_done is normal termination
        return 1 if reason == "reward_engine_done" else 0
    if metric == "dist_to_goal":
        # We don't have dist_to_goal in entry; could be extended from obs. Leave None.
        return None
    return None


async def run_trial(
    ws,
    scenario: dict,
    trial_index: int,
    seed: int,
    max_steps: int,
    episode_timeout_s: float,
    tick_hz: float,
    data_dir: Path,
    episode_history_path: Path,
    bridge_metrics_path: Path,
) -> dict:
    """Run one trial: start episode (if trial > 0), send observations, collect metrics."""
    # Start next episode for trial > 0
    if trial_index > 0:
        await ws.send(json.dumps({
            "proto": "1",
            "kind": "eval_control",
            "seq": trial_index,
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
    await asyncio.sleep(0.5)  # Let policy write episode_history

    entry = read_episode_history_last(episode_history_path)
    metrics_rows = read_bridge_metrics_in_window(bridge_metrics_path, t_start, t_end)
    agg = aggregate_metrics(metrics_rows)

    steps = entry.get("episode_step") or entry.get("steps") or step
    duration_s = entry.get("duration_seconds")
    if duration_s is None and entry:
        duration_s = round(t_end - t_start, 2)
    success = infer_success(entry or {}, scenario)
    if success is None and entry:
        success = 1 if (entry.get("done_reason") or entry.get("reason")) == "reward_engine_done" else 0

    return {
        "scenario": scenario.get("name", "unknown"),
        "trial": trial_index + 1,
        "seed": seed,
        "success": success if success is not None else "",
        "steps": steps,
        "episode_duration_s": round(duration_s, 2) if duration_s is not None else "",
        "collisions": 0,  # Not in episode_history yet
        "queue_obs_high_watermark": agg["queue_obs_high_watermark"],
        "action_timeouts": agg["action_timeouts"],
        "reconnects": agg["reconnects"],
    }


async def run_eval(
    start_bridge: bool,
    bridge_host: str,
    bridge_port: int,
    scenario_filter: str | None,
    max_trials_per_scenario: int | None,
    reports_dir: Path,
    data_dir: Path,
    eval_cfg: dict,
    bridge_metrics_path: Path,
    episode_history_path: Path,
    overlay_path: Path,
    tick_hz: float,
) -> list[dict]:
    """Main: connect, run scenarios/trials, return rows for CSV."""
    if start_bridge:
        data_dir.mkdir(parents=True, exist_ok=True)
        save_yaml(overlay_path, {"control_mode": "PLAYER"})
        proc = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "ai.src.app.server",
            ],
            cwd=str(_PROJECT_ROOT),
            env={**os.environ, "PYTHONPATH": str(_PROJECT_ROOT), "APP_ENV": "prod"},
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        for _ in range(50):
            await asyncio.sleep(0.2)
            try:
                async with websockets.connect(f"ws://{bridge_host}:{bridge_port}") as _:
                    break
            except Exception:
                continue
        else:
            proc.terminate()
            raise RuntimeError("Bridge did not become ready in time")
    else:
        proc = None

    all_rows: list[dict] = []
    scenarios = [s for s in eval_cfg.get("scenarios", []) if s.get("enabled", True)]
    if scenario_filter:
        scenarios = [s for s in scenarios if s.get("name") == scenario_filter]
    if not scenarios:
        print("No scenarios to run.")
        if proc:
            proc.terminate()
        return all_rows

    try:
        async with websockets.connect(f"ws://{bridge_host}:{bridge_port}") as ws:
            # Hello as client with PLAYER so server accepts us
            hello = {
                "proto": "1",
                "kind": "hello",
                "seq": 0,
                "timestamp": time.time(),
                "role": "client",
                "control_mode": "PLAYER",
            }
            await ws.send(json.dumps(hello))
            await asyncio.sleep(0.3)

            term_default = eval_cfg.get("termination", {})
            for scenario in scenarios:
                name = scenario.get("name", "unknown")
                episodes_cfg = scenario.get("episodes", {})
                trials = int(episodes_cfg.get("trials", 10))
                seeds = list(episodes_cfg.get("seeds", [42]))
                if max_trials_per_scenario is not None:
                    trials = min(trials, max_trials_per_scenario)
                term = scenario.get("termination", term_default)
                max_steps = int(term.get("max_steps", 1200))
                episode_timeout_s = float(term.get("episode_timeout_s", 120))

                # Align policy max_steps with scenario (config_update payload is merged into runtime overlay)
                await ws.send(json.dumps({
                    "proto": "1",
                    "kind": "config_update",
                    "seq": 0,
                    "timestamp": time.time(),
                    "payload": {
                        "policy": {
                            "reward": {"max_steps_per_episode": max_steps},
                        },
                    },
                }))
                await asyncio.sleep(0.2)

                for trial in range(trials):
                    seed = seeds[trial % len(seeds)]
                    try:
                        row = await run_trial(
                            ws,
                            scenario,
                            trial,
                            seed,
                            max_steps,
                            episode_timeout_s,
                            tick_hz,
                            data_dir,
                            episode_history_path,
                            bridge_metrics_path,
                        )
                    except Exception as e:
                        print(f"  {name} trial {trial + 1}/{trials} seed={seed} failed: {e}", file=sys.stderr)
                        row = {
                            "scenario": name,
                            "trial": trial + 1,
                            "seed": seed,
                            "success": 0,
                            "steps": 0,
                            "episode_duration_s": "",
                            "collisions": 0,
                            "queue_obs_high_watermark": 0,
                            "action_timeouts": 0,
                            "reconnects": 0,
                        }
                    all_rows.append(row)
                    print(f"  {name} trial {trial + 1}/{trials} seed={seed} steps={row['steps']} success={row['success']}")
    finally:
        if proc:
            proc.terminate()

    return all_rows


def write_csv(path: Path, rows: list[dict], columns: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=columns, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


def plot_success_by_scenario(rows: list[dict], out_path: Path) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return
    from collections import defaultdict
    by_scenario = defaultdict(list)
    for r in rows:
        if r.get("success") != "":
            by_scenario[r["scenario"]].append(int(r["success"]))
    scenarios = sorted(by_scenario.keys())
    rates = [sum(by_scenario[s]) / len(by_scenario[s]) * 100 if by_scenario[s] else 0 for s in scenarios]
    plt.figure(figsize=(6, 4))
    plt.bar(scenarios, rates, color="steelblue")
    plt.ylabel("Success rate (%)")
    plt.title("Success rate by scenario")
    plt.tight_layout()
    plt.savefig(out_path, dpi=100)
    plt.close()


def plot_steps_distribution(rows: list[dict], out_path: Path) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return
    steps = [r["steps"] for r in rows if r.get("steps") != ""]
    if not steps:
        return
    plt.figure(figsize=(6, 4))
    plt.hist(steps, bins=min(30, len(set(steps)) or 1), color="steelblue", edgecolor="white")
    plt.xlabel("Steps")
    plt.ylabel("Count")
    plt.title("Steps distribution")
    plt.tight_layout()
    plt.savefig(out_path, dpi=100)
    plt.close()


def plot_queue_watermarks(rows: list[dict], out_path: Path) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return
    obs_wm = [r.get("queue_obs_high_watermark", 0) for r in rows]
    if not obs_wm:
        return
    plt.figure(figsize=(6, 4))
    plt.plot(obs_wm, color="steelblue", label="queue_obs_high_watermark")
    plt.xlabel("Trial index")
    plt.ylabel("High watermark")
    plt.legend()
    plt.title("Queue high watermarks")
    plt.tight_layout()
    plt.savefig(out_path, dpi=100)
    plt.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluation runner (scenario-driven)")
    parser.add_argument("--start-bridge", action="store_true", help="Start bridge subprocess before connecting")
    parser.add_argument("--host", default="127.0.0.1", help="Bridge host")
    parser.add_argument("--port", type=int, default=8765, help="Bridge port")
    parser.add_argument("--scenario", type=str, default=None, help="Run only this scenario name")
    parser.add_argument("--max-trials", type=int, default=None, help="Cap trials per scenario")
    args = parser.parse_args()

    if websockets is None:
        print("Install websockets: pip install websockets", file=sys.stderr)
        return 1

    eval_path = _CONFIG_DIR / "evaluation.yaml"
    eval_cfg = load_yaml(eval_path)
    if not eval_cfg:
        print("evaluation.yaml not found or empty.", file=sys.stderr)
        return 1

    metrics_cfg = eval_cfg.get("metrics", {})
    bridge_metrics_path_str = metrics_cfg.get("bridge_metrics_path", "logs/bridge_metrics.ndjson")
    bridge_metrics_path = _DATA_DIR / bridge_metrics_path_str if not Path(bridge_metrics_path_str).is_absolute() else Path(bridge_metrics_path_str)
    episode_history_path = _DATA_DIR / "episode_history.json"
    report_cfg = eval_cfg.get("report", {})
    reports_dir = _PROJECT_ROOT / report_cfg.get("out_dir", "reports")
    csv_name = report_cfg.get("csv_name", "eval.csv")
    overlay_path = _DATA_DIR / "runtime_overrides.yaml"
    tick_hz = 20.0

    rows = asyncio.run(run_eval(
        start_bridge=args.start_bridge,
        bridge_host=args.host,
        bridge_port=args.port,
        scenario_filter=args.scenario,
        max_trials_per_scenario=args.max_trials,
        reports_dir=reports_dir,
        data_dir=_DATA_DIR,
        eval_cfg=eval_cfg,
        bridge_metrics_path=bridge_metrics_path,
        episode_history_path=episode_history_path,
        overlay_path=overlay_path,
        tick_hz=tick_hz,
    ))

    if not rows:
        return 0

    columns = [
        "scenario", "trial", "seed", "success", "steps", "episode_duration_s",
        "collisions", "queue_obs_high_watermark", "action_timeouts", "reconnects",
    ]
    csv_path = reports_dir / csv_name
    write_csv(csv_path, rows, columns)
    print(f"Wrote {csv_path}")

    plots_cfg = report_cfg.get("plots", {})
    if plots_cfg.get("success_by_scenario"):
        plot_success_by_scenario(rows, reports_dir / "success_by_scenario.png")
        print(f"Wrote {reports_dir / 'success_by_scenario.png'}")
    if plots_cfg.get("steps_distribution"):
        plot_steps_distribution(rows, reports_dir / "steps_distribution.png")
        print(f"Wrote {reports_dir / 'steps_distribution.png'}")
    if plots_cfg.get("queue_watermarks"):
        plot_queue_watermarks(rows, reports_dir / "queue_watermarks.png")
        print(f"Wrote {reports_dir / 'queue_watermarks.png'}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
