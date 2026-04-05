# Evaluation runner (scenario-driven runs)

The evaluation runner runs scenario-driven evaluation defined in `configs/evaluation.yaml`: it connects to the bridge as a client, runs N trials per scenario (with seeds), applies termination (max_steps, episode_timeout_s), records success/steps/collisions/queue stats, and writes **eval.csv** plus optional plots under `reports/`.

## Prerequisites

- **websockets**: `pip install websockets`
- Optional (for plots): `pip install matplotlib`

## Usage

From the **ai_agent_project** directory (or with `PYTHONPATH` set to it):

```bash
# Use an existing bridge (must be running with control_mode=PLAYER, or set via overlay)
PYTHONPATH=. python -m scripts.run_eval

# Start the bridge automatically (writes control_mode=PLAYER to shared/Data/runtime_overrides.yaml, then starts the server)
PYTHONPATH=. python -m scripts.run_eval --start-bridge

# Limit to one scenario and cap trials
PYTHONPATH=. python -m scripts.run_eval --scenario reach_goal_basic --max-trials 5
```

If you run from the repo root with `ai_agent_project` on `PYTHONPATH`:

```bash
PYTHONPATH=ai_agent_project python ai_agent_project/scripts/run_eval.py --start-bridge
```

## What it does

1. **Loads** `evaluation.yaml` and uses bridge host/port from default config (or `--host` / `--port`).
2. **For each enabled scenario** (or `--scenario`):
   - Sends `config_update` so `policy.reward.max_steps_per_episode` matches the scenario’s `termination.max_steps`.
   - For each **trial**: for trial 0 the server has already started an episode on hello; for trial &gt; 0 sends **eval_control** `action: start_episode` so the server starts the next episode without sending `episode_end`.
   - Sends **mock observations** at the policy tick rate for up to `max_steps` or `episode_timeout_s`.
   - Reads **episode_history.json** (last entry) and **bridge_metrics** NDJSON in the trial time window.
3. **Records** per trial: scenario, trial, seed, success, steps, episode_duration_s, collisions, queue_obs_high_watermark, action_timeouts, reconnects.
4. **Writes** `reports/eval.csv` and, if `report.plots` in evaluation.yaml is true, PNGs: `success_by_scenario`, `steps_distribution`, `queue_watermarks`.

## Server: eval_control

The bridge accepts a message `kind: "eval_control"` with `payload: { "action": "start_episode" }`. This calls `start_new_episode()` (same as after client `episode_end`) so the evaluation runner can start the next trial without sending `episode_end` and without the policy appending a duplicate episode_history entry.

## Alignment with evaluation.yaml

- **Scenarios**: Only runs scenarios with `enabled: true` (or the one given by `--scenario`).
- **Termination**: Uses each scenario’s `termination.max_steps` and `termination.episode_timeout_s`; `no_progress` is not enforced by the runner (policy/reward engine can use it).
- **Success**: Inferred where possible (e.g. `survived` → success if `done_reason == "reward_engine_done"`); `dist_to_goal` is not computed by the runner.
- **Metrics**: Columns match `metrics.capture` (success, steps, collisions, queue_obs_high_watermark, action_timeouts, reconnects); bridge_metrics path comes from `metrics.bridge_metrics_path` (resolved relative to `shared/Data`).
