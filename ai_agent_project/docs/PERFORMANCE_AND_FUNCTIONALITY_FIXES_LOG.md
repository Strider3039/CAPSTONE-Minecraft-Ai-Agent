# Performance and functionality fixes log

This document records **important fixes** that affect bridge behavior, control mode, UI/config, logging noise, and **end-to-end latency**. Entries are append-only by date.

---

## 2026-03-30 — Latency, queues, training path, and observability

### Problem (symptoms)

- **`latency_stats`** showed **multi-second** `p50_ms` / `p90_ms` (e.g. 9–25s).
- **`shared/Data/logs/bridge_metrics.ndjson`** showed **`queue_obs_high_watermark: 128`**, **`obs_dropped`** in the hundreds/thousands, **`queue_act_size` pegged at `act_max` (64)**, and rising **`action_timeouts`**.
- **Root cause (combined):**
  1. **`OnlineDQNPolicy.act`** ran **`TrainStep` + `UpdateTargetNetwork`**, **full JSON episode state rewrite**, and **`step_history.jsonl` append** on **every** env step — all **synchronous** on the asyncio thread, so the policy loop fell behind incoming observations.
  2. When **`act_q` filled**, **`PolicyWorker`** blocked on **`await act_q.put(...)`**, stopped dequeuing observations, and **`obs_q` backed up** → processed observations had **stale `timestamp`s** → huge reported latency.
  3. **Multiple WebSocket sessions** on the same bridge process each run a **full** policy + workers → CPU overload and queue saturation (e.g. integrated client **and** dedicated server both connected).

### Fixes applied (config + Python)

| Area | Change | Files |
|------|--------|--------|
| Action backlog | **`act_max`**: 64 → **256** | `shared/config/default.yaml` |
| Drain actions faster | **`max_actions_per_tick`**: 3 → **12** | `shared/config/default.yaml` |
| Cheaper training loop | **`train_every_n: 2`** — gradient + target update on every Nth transition (still **stores** every transition) | `default.yaml`, `registry.py`, `ai/src/policy/rl/dqn/agent.py` |
| Less disk I/O | **`log_disk_every_n: 5`** — throttle `online_dqn_episode_state.json` + `step_history.jsonl` (always log on **`done`**) | same |
| Hot-reload | **`apply_runtime_config`** reads **`train_every_n`** / **`log_disk_every_n`** | `agent.py` |
| Multi-session warning | Log **WARNING** if more than one bridge session starts a full policy on the process | `ai/src/app/server.py` (`_active_policy_bridge_sessions`) |

### Outcome (expected)

- **`tick_latency`** **`p50_ms`** in the **~150–200 ms** range (typical) instead of seconds, when **one** game peer is connected and queues are healthy.
- **`bridge_metrics`** should show **`queue_act_size`** well below **`act_max`**, **`queue_obs_high_watermark`** not stuck at max, **`obs_dropped`** low.

### Operational note

- For **PLAYER + integrated server**, connect **only the client** to the bridge on **`8765`** unless you intentionally run two policies. If two sessions attach, check bridge logs for the **multiple sessions** warning.

---

## 2026-03-30 — Dedicated server debug log spam

### Problem

- **`ServerBridgeWebSocketClient.ensureConnected`** logged **`skip|backoff waitMs=...`** every tick because the dedupe key included a **changing** millisecond value.
- **`sendJson SKIP (socket not open)`** fired **every observation** when the bridge was down.

### Fixes

- Backoff: **stable** dedupe key; log **once** per backoff period with a single **~N ms** hint.
- **`sendJson`**: throttle SKIP lines to **at most once per 5s** with suppressed count.

**Files:** `bot/forge/.../server/ServerBridgeWebSocketClient.java`

---

## 2026-03-30 — Config UI vs `runtime_overrides.yaml`

### Problems

- Opening the UI **forced PLAYER** when the file still had **`control_mode: PLAYER`** but the user had chosen **SERVER_BOT** and **Apply** could not reach Python (WS closed) — disk lagged behind the UI.
- User expectation: **disk overlay matches the UI** without waiting for a successful **`config_update`** to the bridge.

### Fixes

- **Do not** downgrade **in-memory SERVER_BOT** to **PLAYER** solely because the yaml still says **PLAYER** (stale overlay).
- **Persist** full overlay from the UI: **`persistRuntimeOverlayFromUi()`** writes **`runtime_overrides.yaml`** on mode change, slider changes (debounced), **Apply**, **Done**, and after **`copyFrom`** flows (add/remove mob, captions toggle).
- **Hydrate** scalars and mob/block maps from an **existing** yaml on screen open so opening the UI does not **overwrite** hand-tuned values with Java defaults.
- **`copyFrom`**: skip one hydration and **persist after `init()`** so in-memory edits are not wiped.

**Files:** `bot/forge/.../client/gui/AiBotConfigScreen.java`

---

## 2026-03-30 — Debug visibility (control mode and bridge)

### Additions

- **`ForgeWebSocketClient`**: log **ControlMode** changes; client **hello** / **`sendConfigUpdate`** skip vs ok.
- **`ClientBridgeHooks`**: **`ensureBridgeConnected`** state summary (deduped); **LoggingIn** / remote dedicated **LoggingOut**.
- **`C2SRuntimeConfigPacket`**: payload/bridge-null branches and payload summary.
- **`ServerBridgeWebSocketClient`**: **`setAutoConnect`**, **`pauseForPlayerMode`**, **`ensureConnected`**, **`sendConfigUpdate`**, server **hello**.
- **`ServerBotHooks`**: player login/logout and **pause** when last player leaves.

**Purpose:** Trace **PLAYER** vs **SERVER_BOT**, client vs server WS, and **C2S** path without guessing.

---

## Earlier session (summary) — Control mode and bridge roles

The following behaviors were aligned so mode is predictable (details live in code and `server.py` / Forge mods):

- **`control_mode`** from **runtime overlay / hot-reload**, not spoofed via **hello**.
- **PLAYER**: client bridge only; **SERVER_BOT** on **dedicated MP**: server bridge only (client does not open a competing WS).
- **Dedicated server** bridge **pause** when empty / **PLAYER**; resume on login / **SERVER_BOT** config as implemented in **`ServerBridgeWebSocketClient`**, **`C2SRuntimeConfigPacket`**, **`ServerBotHooks`**, **`ClientBridgeHooks`**.

*(Add more dated bullets here as you land future fixes.)*

---

## 2026-03-30 — Latency stats “stuck high” after recovery

### Problem

- **`latency_stats`** sometimes stayed at **~15s** **p50** even when play felt normal again.
- **Causes:**
  1. **Real backlog again** (two bridge connections, `act_q` full, slow `act()`, world save / shutdown) → observations truly **old** when processed.
  2. **Metric hysteresis:** **`latSamplesMs`** kept up to **200** samples; **median** stayed inflated until **>50%** of the buffer were “good” samples.

### Fixes

- **`latency_stats`** now uses only the **last N** samples (default **48**, config **`bridge.queues.latency_stats_recent_samples`**). Detail string includes **`window=N`**.
- **Throttled warning** **`stale_observation`** (≥**5s** age, at most every **10s**) with **`obs_q` / `act_q` sizes** so logs/metrics point at backlog.

**Files:** `ai/src/app/policy_worker.py`, `shared/config/default.yaml`

---

## How to use this log

1. After significant behavior or performance work, add a **dated section** with **problem → fix → files → verification**.
2. Prefer **one line per user-visible symptom** in “Problem” and **measurable** checks in “Outcome” (metrics file paths, log tags).
