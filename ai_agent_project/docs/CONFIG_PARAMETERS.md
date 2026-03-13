# 2.1 Parameters exposed and tested

All parameters below live under `shared/config/default.yaml` (and overrides in `dev.yaml`, `evaluation.yaml`, or hot-reload overlay in `shared/Data/runtime_overrides.yaml`). The bridge merges `runtime` with the overlay and passes it to the policy worker and policy (e.g. DQN).

---

## Observation settings (`runtime.obs`)

| Parameter | Type | Default | Where used | Notes |
|-----------|------|---------|------------|--------|
| `rate_hz` | float | 12 | **Intended:** client observation send rate. **Current:** Forge client uses a fixed **100 ms** interval (~10 Hz); this value is exposed for future hello→client sync and validation. | Observation send interval (Hz). |
| `entity_cap` | int | 8 | **Intended:** cap on entities included in observation. Client could trim `entities_nearby` to this count. | Max entities in obs payload. |
| `include.pose` | bool | true | Client builds observation payload; server validates against observation schema. | Include position, yaw, pitch. |
| `include.world_time_weather_biome` | bool | true | Same. | Time of day, weather, biome. |
| `include.inventory_hotbar_summary` | bool | true | Same. | Hotbar / inventory summary. |
| `include.entities_nearby` | bool | true | Same. | Nearby entities (capped by `entity_cap` when applied). |
| `include.los_flags` | bool | true | Same. | Line-of-sight / ray flags. |
| `quantization.pos_decimals` | int | 2 | Client or server encoding; reduces payload size. | Decimal places for position. |
| `quantization.angle_decimals` | int | 2 | Same. | Decimal places for angles. |
| `quantization.dist_decimals` | int | 2 | Same. | Decimal places for distances. |

**Testing:** Config load test asserts `runtime.obs` exists with `rate_hz`, `entity_cap`, `include.*`, `quantization.*`. Client-side use of `runtime.obs` (e.g. from hello) is optional for 2.1; when added, the same keys should be sent in the hello response so the client can apply `rate_hz` and caps.

---

## Policy parameters (`runtime.policy`)

| Parameter | Type | Default | Where used | Notes |
|-----------|------|---------|------------|--------|
| `type` | string | "online_dqn" | `registry.build_policy_from_config()` | Policy type: `online_dqn`, `dqn`. |
| `tick_hz` | float | 20 | `policy_worker.PolicyWorker` | Policy loop frequency (Hz). |
| `budget_ms` | int | 80 | Reserved for future per-tick compute budget. | Max ms per policy step. |
| `max_actions_per_tick` | int | 3 | `server.py` action sender (continuous action cap). | Max continuous actions per tick. |
| `max_ray_dist` | float | 20 | `registry` → DQN agent / obs encoding | Ray distance for observation encoding. |
| `success_radius` | float | 1.5 | Reserved for navigation / goal tasks. | Distance threshold for “success”. |
| `stuck_speed_thresh` | float | 0.02 | Mock envs (e.g. ethan_gym); can be used by live reward/stopping. | Speed below which counts as “stuck”. |
| `stuck_ticks` | int | 15 | Same. | Consecutive slow ticks before stuck penalty/trigger. |
| `heading.max_look_deg` | float | 20.0 | Reserved for heading/navigation helpers. | Max look delta per step (deg). |
| `heading.yaw_p_gain` | float | 1.0 | Same. | Proportional gain for yaw correction. |
| `heading.stop_on_collision` | bool | true | Same. | Stop or adjust on collision. |
| `action_rates.look_hz` | float | 20 | Can drive rate limits or smoothing. | Look rate cap (Hz). |
| `action_rates.move_hz` | float | 20 | Same. | Move rate cap (Hz). |
| `action_rates.jump_min_ms` | int | 200 | Cooldown between jump actions. | Min ms between jumps. |
| `action_rates.interact_cooldown_ms` | int | 500 | Cooldown for use/attack. | Min ms between interact actions. |

**Testing:** Config test asserts `runtime.policy` and nested `heading`, `action_rates` exist; `tick_hz` is read in `policy_worker.py`; `max_ray_dist` is read in `registry.py` and DQN agent.

---

## Reward (`runtime.policy.reward`)

Hot-reload: `OnlineDQNPolicy.apply_runtime_config()` → `RewardEngine.apply_config()` → each component’s `apply_config()`.

| Parameter | Type | Default | Where used | Notes |
|-----------|------|---------|------------|--------|
| `survival_reward` | float | 0.001 | `GenericRewardComponent` | Per-step survival bonus. |
| `step_penalty` | float | -0.001 | Same. | Per-step cost. |
| `move_scale` | float | 1.0 | Same. | Scale for movement reward. |
| `max_move_reward` | float | 0.1 | Same. | Cap on move reward per step. |
| `no_progress_penalty` | float | -0.02 | Same. | Penalty when no progress (e.g. collision). |
| `front_clear_bonus` | float | 0.005 | Same. | Bonus for front clear. |
| `item_pickup_reward` | float | 0.05 | Same. | Reward for item pickup. |
| `max_steps_per_episode` | int | 2000 | `RewardEngine` | Episode step limit; triggers done. |
| `blocks` | dict | {} | `BlockRewardComponent` | Block id → reward (e.g. diamond_ore: 1.0). |
| `mobs` | dict | {} | `MobRewardComponent` | Entity type → reward. |

**Testing:** Config test asserts `runtime.policy.reward` and the generic keys above exist. Hot-reload is covered by config_update flow and `apply_runtime_config`.

---

## DQN (`runtime.policy.dqn`)

Hot-reload: same overlay → `OnlineDQNPolicy.apply_runtime_config()` → `DQNAgent.apply_config()`.

| Parameter | Type | Default | Where used | Notes |
|-----------|------|---------|------------|--------|
| `checkpoint_path` | string | (see default.yaml) | `registry.build_policy_from_config()` | Initial or resume checkpoint. |
| `epsilon_start` | float | 1.0 | `DQNAgent.apply_config()` | Epsilon at start of decay. |
| `epsilon_end` | float | 0.1 | Same. | Epsilon at end of decay. |
| `epsilon_decay` | int | 100000 | Same. | Steps over which epsilon decays. |
| `gamma` | float | 0.99 | Same. | Discount factor. |
| `lr` | float | 0.001 | Same. | Learning rate. |
| `batch_size` | int | 64 | Same. | Replay batch size. |
| `min_replay_size` | int | 1000 | Same. | Min buffer size before training. |
| `target_update_freq` | int | 1000 | Same. | Steps between target net updates. |

**Testing:** Config test asserts `runtime.policy.dqn` exists and contains `epsilon_start`, `epsilon_end`, `gamma`, `lr`, `batch_size`. Hot-reload is applied on `config_update` and on hello.

---

## Raycasts (`runtime.raycasts`)

Used by registry and observation encoding for ray-based features.

| Parameter | Type | Default | Where used | Notes |
|-----------|------|---------|------------|--------|
| `count` | int | 8 | Observation encoding / ray logic. | Number of rays. |
| `max_dist` | float | 6.0 | `registry` (fallback for `max_ray_dist`), obs encoding | Max ray distance. |
| `fov_deg` | float | 60 | Ray spread. | Field of view (degrees). |
| `front_clear_threshold` | float | 1.3 | Front-clear / collision logic. | Distance for “front clear”. |

**Testing:** Config test asserts `runtime.raycasts` exists with `max_dist` (and optionally `count`, `fov_deg`, `front_clear_threshold`).

---

## Bridge metrics (3.1)

The bridge writes NDJSON metric rows when `bridge.metrics.enabled` is true. The sink path (e.g. `logs/bridge_metrics.ndjson`) is **resolved relative to `shared/Data`**, so the file is written to a known location (e.g. `ai_agent_project/shared/Data/logs/bridge_metrics.ndjson`). Each row includes:

- **Queue / reliability**: `queue_obs_size`, `queue_obs_high_watermark`, `obs_dropped`, `queue_act_size`, `queue_act_high_watermark`, `action_timeouts`
- **Tick latency** (when policy emits): `type: "tick_latency"` rows with `p50_ms`, `p90_ms`, `hz`; periodic rows also include `tick_latency_p50_ms`, `tick_latency_p90_ms`, `tick_latency_hz` when set
- **Throughput**: `obs_per_sec`, `acts_per_sec` (rolling window)
- **Reconnects**: `type: "connection"` rows with `connection_count`, `event: "connected"` on each client connection

See README “Bridge metrics and logs” and [HOT_RELOAD_AND_PATHS.md](HOT_RELOAD_AND_PATHS.md) for run/path requirements.

---

## Episode-level metrics (3.2)

Per-episode data supports "N episodes under config A vs B" and aligns with `shared/config/evaluation.yaml` (success rate, episode duration, task completion steps).

**Locations (under `shared/Data/`):**

- **`episode_state.json`** — overwritten each policy step with the *current* running episode: `episode_id`, `episode_step`, `episode_return`, `done`, plus pose/world/collision and `logged_at_unix`. Use for live inspection; the canonical per-episode record is `episode_history.json`.
- **`episode_history.json`** — one entry per *finished* episode (append-only). Each entry includes:
  - **episode_id** — same as Python episode index.
  - **episode_step** — number of steps until termination.
  - **episode_return** — sum of rewards in the episode.
  - **done_reason** — e.g. `reward_engine_done`, `client_episode_end`.
  - **duration_seconds** — wall-clock time for the episode (from episode start to end).
  - **success** — optional; set by evaluation runner or left `null` when unknown.

Backward-compatible keys (`episode`, `steps`, `return`, `reason`) are still written. Episode start time is set on `episode_start` or on first observation when no explicit start was received.

**Alignment with evaluation.yaml:** Scenarios in `evaluation.yaml` define `success.metric` (e.g. `dist_to_goal`, `survived`), `termination` (max_steps, timeout, no_progress), and `metrics.capture` (success, steps, etc.). The DQN agent writes **episode_step** (task completion steps), **duration_seconds** (episode duration), and **done_reason**; **success** can be filled by the evaluation runner or by a rule (e.g. `done_reason` + return threshold) when integrated.

---

## How to test

1. **Config load (unit):**  
   Run: `pytest ai_agent_project/tests/test_config.py -v`  
   Includes `test_runtime_parameters_exposed`: loads project default config and asserts presence (and basic types) of `runtime.obs`, `runtime.policy` (including `heading`, `action_rates`, `dqn`, `reward`), and `runtime.raycasts`.

2. **Hot-reload:**  
   Send a `config_update` message over the bridge with a payload that overrides e.g. `policy.reward.step_penalty` or `policy.dqn.epsilon_start`; confirm the policy’s reward engine and DQN agent reflect the new values on the next step.

3. **Observation rate (client):**  
   Currently the Forge client sends observations every 100 ms. To make `runtime.obs.rate_hz` effective on the client, the server would send `runtime.obs` (or a subset) in the hello response and the client would set its send interval from `rate_hz` (e.g. `1000 / rate_hz` ms). This is optional for 2.1.
