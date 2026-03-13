# Experimental data for conference

Scripts and outputs for gathering baseline, parameter-sensitivity, and stability data before the conference.

## Overview

| Experiment | Purpose | Output |
|------------|----------|--------|
| **Baseline** | Default config, 20+ episodes; success rate, mean episode length, mean p50/p90 latency, disconnects | `reports/baseline_run.json` |
| **Parameter sensitivity** | 3–5 runs each with one changed parameter; same metrics to show observable influence | `reports/parameter_sensitivity.csv` |
| **Stability** | Long run (100+ episodes or 1–2 h); latency and disconnects over time to show no memory growth or drift | `reports/stability_run.json` |

## Prerequisites

- Bridge and (for real gameplay) Minecraft client, or use **--start-bridge** to run the bridge only (mock observations).
- `pip install websockets` (and optional `matplotlib` for any plotting).

From **ai_agent_project** with `PYTHONPATH=.`:

## 1. Baseline run

Default config, 25 episodes (configurable). Records success rate, mean episode length, mean episode duration, mean p50/p90 latency, and disconnects.

```bash
PYTHONPATH=. python -m scripts.run_experiments baseline [--episodes 25] [--start-bridge]
```

**Output** (`reports/baseline_run.json`):

- `success_rate`, `mean_episode_length`, `mean_episode_duration_s`
- `mean_p50_ms`, `mean_p90_ms` (from bridge metrics)
- `disconnects`
- `per_episode`: list of episode-level success, steps, duration

## 2. Parameter sensitivity

For each of 4 parameters, run 3 (or 5) runs per value; each run is 20 episodes. Parameters:

- **tick_hz**: 10 vs 20
- **step_penalty**: -0.001 vs -0.01
- **epsilon_end**: 0.1 vs 0.5
- **obs rate_hz**: 6 vs 12

```bash
PYTHONPATH=. python -m scripts.run_experiments sensitivity [--runs-per-value 3] [--episodes-per-run 20] [--start-bridge]
```

**Output** (`reports/parameter_sensitivity.csv`): columns `parameter`, `value`, `run_id`, `success_rate`, `mean_episode_length`, `mean_p50_ms`, `mean_p90_ms`, `disconnects`. Use this to show “observable influence of parameters.”

## 3. Stability run

Run until 100 episodes or 2 hours (whichever first). Every 10 episodes, record latency (p50/p90), queue high watermark, and disconnect count.

```bash
PYTHONPATH=. python -m scripts.run_experiments stability [--max-episodes 100] [--max-hours 2] [--sample-every 10] [--start-bridge]
```

**Output** (`reports/stability_run.json`):

- `total_episodes`, `total_wall_clock_s`, `disconnects`
- `time_series`: list of samples with `episode`, `wall_clock_s`, `mean_p50_ms`, `mean_p90_ms`, `queue_obs_high_watermark`, `disconnects_so_far`

Check that mean_p50_ms / mean_p90_ms do not increase over time (no latency drift) and disconnects remain low (no unexplained disconnects). Memory growth would require monitoring the bridge process separately (e.g. `psutil` or OS tools).

## Options (all modes)

- **--host**, **--port**: Bridge address (default `127.0.0.1:8765`).
- **--start-bridge**: Start the bridge subprocess and set `control_mode=PLAYER` in the overlay before connecting.
- **--reports-dir**: Directory for output files (default `reports`).

## Interpreting results

- **Baseline**: Use as the reference “default” numbers for success rate, episode length, and latency.
- **Sensitivity**: Compare success_rate and mean_p50_ms / mean_p90_ms across parameter values to show that changing tick_hz, step_penalty, epsilon, or obs rate has a measurable effect.
- **Stability**: Use the time_series to show that latency and disconnects are stable over 100+ episodes or 1–2 hours (no memory growth, no increase in latency over time).
