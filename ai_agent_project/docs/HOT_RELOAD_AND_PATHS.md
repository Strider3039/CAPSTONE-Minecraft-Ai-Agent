# 2.2 Hot-reload, overlay persistence, and paths

## Hot-reload flow (verified)

End-to-end path from GUI to live policy:

1. **GUI** (`AiBotConfigScreen`): User changes e.g. **Step penalty**, **Epsilon**, or reward sliders, then clicks **Apply**.
2. **Apply handler** builds a runtime overlay payload (`buildConfigPayload()`): `control_mode`, `policy.reward` (survival_reward, step_penalty, move_scale, max_move_reward, no_progress_penalty, front_clear_bonus, item_pickup_reward, max_steps_per_episode, blocks, mobs), `policy.dqn` (epsilon_start).
3. **Client → bridge**: `ForgeWebSocketClient.sendConfigUpdate(payload)` sends a WebSocket message `{"proto":"1","kind":"config_update","payload": <payload>}`. (Used in singleplayer where the client holds the WebSocket.)
4. **Client → server** (multiplayer): `C2SRuntimeConfigPacket` sends the same JSON to the server; server calls `ServerBridgeWebSocketClient.sendConfigUpdate(payload)` so the dedicated server’s bridge connection receives the update.
5. **Bridge** (`server.py`, `kind == "config_update"`): Merges `msg["payload"]` into `runtime_overlay`, calls `refresh_current_runtime()` (base config + overlay), then `policy.apply_runtime_config(current_runtime)` so the live DQN/reward engine gets the new values. No bridge restart.
6. **Persistence**: Bridge calls `save_runtime_overlay(runtime_overlay)` so the next connection (or restart) uses the same overlay.

**How to verify:** Change **Step penalty** to a more negative value (e.g. -0.01) in the GUI and click **Apply**. Let the agent run for a minute: you should see it move less (higher cost per step). Change **Epsilon** lower (e.g. 0.2) and **Apply**: behavior should become more greedy/exploit-heavy. No need to restart the bridge.

---

## Runtime overlay path and data dir

- **Overlay file**: `shared/Data/runtime_overrides.yaml`.
- **Resolved in code**: `server.py` sets `dataDir = project_root / "shared" / "Data"` and `RUNTIME_OVERLAY_PATH = dataDir / "runtime_overrides.yaml"`. So the overlay is always under the **shared Data** directory.
- **project_root**: Derived from `server.py`’s `__file__` as `server_root.parents[3]`, which is intended to be the repo root that contains `ai_agent_project` (or the `ai_agent_project` directory itself when the `ai` package lives at `ai_agent_project/ai`). So `shared/Data` is `project_root/shared/Data`.

---

## Bridge CWD and finding config / Data

- **Config**: Loaded via `ai.src.utils.config`, which uses `CONF_DIR = SHARED / "config"` with `SHARED` derived from the `ai` package location (e.g. `ai_agent_project/shared`). So `shared/config/default.yaml` (and env overrides) must be under that shared dir.
- **Data / overlay**: The bridge uses `server.py`’s `project_root` to form `sharedDir` and `dataDir`, so `shared/Data` and `runtime_overrides.yaml` are found without relying on the process CWD.
- **Recommended run**: Start the bridge so that the `ai` package is importable and lives under the project that contains `shared/`. For example, from repo root:  
  `PYTHONPATH=ai_agent_project python -m ai.src.app.server`  
  or from `ai_agent_project`:  
  `python -m ai.src.app.server`  
  (with `ai_agent_project` on `PYTHONPATH` so `ai` resolves to `ai_agent_project/ai`). Then `shared/config` and `shared/Data` are resolved from the same project root and the overlay is read/written under `shared/Data/`.

---

## Summary

| Item | Location / behavior |
|------|---------------------|
| Overlay path | `shared/Data/runtime_overrides.yaml` (under project root used by server.py) |
| Load overlay | At start of each WebSocket connection (`load_runtime_overlay()`) |
| Save overlay | After every successful `config_update` (`save_runtime_overlay(runtime_overlay)`) |
| Hot-reload | GUI Apply → `config_update` → merge overlay → `policy.apply_runtime_config(current_runtime)` → save overlay |
