# Hot Reload & Value Syncing: GUI ↔ Bridge ↔ FakePlayer

## Overview

- **Mode (SERVER_BOT / PLAYER)**: Client-authoritative. GUI changes apply immediately on the client via `ForgeWebSocketClient.setControlMode()`; no server “reload” required for who executes actions.
- **DQN / reward params**: Used only in the Python bridge (PolicyWorker → OnlineDQNPolicy → RewardEngine, DQNAgent). Synced by sending a **config_update** over the existing WebSocket from the Minecraft server to the bridge, then applying to the live policy.

## Data flow

```
┌─────────────────┐     C2S packet      ┌─────────────────────┐     WebSocket      ┌─────────────────┐
│  GUI (client)   │ ──────────────────► │  Minecraft server   │ ─────────────────► │  Python bridge  │
│  AiBotConfig    │  RuntimeConfig      │  ServerBridgeWS      │  config_update     │  runtime_overlay │
│  setControlMode │                      │  sendConfigUpdate()  │                    │  policy.apply_* │
└─────────────────┘                     └─────────────────────┘                    └─────────────────┘
        │
        │ (local, immediate)
        ▼
  ForgeWebSocketClient.setControlMode(PLAYER | SERVER_BOT)
```

- **Mode**: GUI → `ForgeWebSocketClient.setControlMode(selectedMode)` on Apply (or on change). Optional: persist to `shared/Data/client_runtime.json` so the GUI can restore last mode.
- **DQN/reward**: GUI → Apply → send C2S `RuntimeConfig` packet (JSON payload) → server → `ServerBridgeWebSocketClient.sendConfigUpdate(payload)` → bridge receives `config_update` → merge into `runtime_overlay` → `policy.apply_runtime_config(merged)`.

## Single source of truth

- **At connection time**: Base config = `default.yaml` (+ env overrides). Persisted overlay = `shared/Data/runtime_overrides.yaml` (loaded if present and merged with base). Policy is built from merged config.
- **After connection**: A **mutable overlay** is merged with the base for “current” runtime. Overlay is updated when the bridge receives a `config_update` message (triggered by GUI Apply); the overlay is then written back to `runtime_overrides.yaml` so the next bridge start uses the same values.
- **Persistence**: Bridge loads `load_runtime_overlay()` at the start of each connection and calls `save_runtime_overlay(runtime_overlay)` after every successful `config_update`. No overwriting of `default.yaml`; the overlay file is separate and merge-only.

## Implementation checklist

1. **Python bridge (`server.py`)**
   - Add `runtime_overlay: dict` inside `Handle()`; when `kind == "config_update"`, merge `msg["payload"]` into `runtime_overlay`, then call `policy.apply_runtime_config(merged)` if policy supports it.
   - Use `merged = DeepMerge(runtime_cfg, runtime_overlay)` wherever “current” config is needed (e.g. when building policy and when applying updates).

2. **Policy (`OnlineDQNPolicy` + `RewardEngine` + `DQNAgent`)**
   - Add `apply_runtime_config(self, runtime_cfg: dict)`: update `reward_engine` fields (e.g. `survival_reward`, `step_penalty`, `max_steps_per_episode`, `item_pickup_reward`, …) and agent fields (e.g. `epsilon_start`, `epsilon_end`, `epsilon_decay`) from `runtime_cfg["policy"]["reward"]` and `runtime_cfg["policy"]["dqn"]`.

3. **Config schema**
   - Extend `default.yaml` (or a separate schema) with optional `runtime.policy.reward` and ensure `runtime.policy.dqn` includes epsilon/replay params so `apply_runtime_config` has a defined structure.

4. **Forge**
   - **C2S packet**: New packet type (e.g. `C2SRuntimeConfigPacket`) carrying a JSON string of runtime overrides. Server handler calls `ServerBridgeWebSocketClient.sendConfigUpdate(jsonPayload)`.
   - **ServerBridgeWebSocketClient**: Add `sendConfigUpdate(JsonObject payload)` that sends `{"proto":"1","kind":"config_update","payload": <payload>}` when the WebSocket is open.
   - **AiBotConfigScreen**: On Apply, call `ForgeWebSocketClient.setControlMode(selectedMode)` and send the new C2S packet with current UI values (control_mode, reward.*, policy.dqn.*).

5. **Optional: load current config in GUI**
   - Option A: S2C “config_sync” — bridge sends merged config to server, server sends to client when GUI opens (requires request/response).
   - Option B: Persist last-applied overrides in a file (e.g. under `shared/Data/`) and have the client read it when opening the GUI (client can read shared folder if mounted, or server can send file contents via a packet once).

## Event ordering

- **Mode**: Changing mode in the GUI and clicking Apply should update `ForgeWebSocketClient` immediately so the next action uses the new mode; no need to wait for the bridge for mode.
- **DQN/reward**: After sending `config_update`, the bridge applies the overlay and updates the policy on the next message; no need to pause the policy loop.

## FakeBotManager

- No change required for hot reload. It only executes actions from the queue; it does not read DQN or reward parameters. Mode only affects whether the client executes locally (PLAYER) or sends to server (SERVER_BOT); FakeBotManager is used only in SERVER_BOT mode.
