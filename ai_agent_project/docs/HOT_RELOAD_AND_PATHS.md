# 2.2 Hot-reload, overlay persistence, and paths

## Hot-reload flow (verified)

End-to-end path from GUI to live policy:

1. **GUI** (`AiBotConfigScreen`): User changes e.g. **Step penalty**, **Epsilon**, or reward sliders, then clicks **Apply**.
2. **Apply handler** builds a runtime overlay payload (`buildConfigPayload()`): `control_mode`, `policy.reward` (survival_reward, step_penalty, move_scale, max_move_reward, no_progress_penalty, front_clear_bonus, item_pickup_reward, max_steps_per_episode, blocks, mobs), `policy.dqn` (epsilon_start).
3. **Client → bridge**: `ForgeWebSocketClient.sendConfigUpdate(payload)` sends a WebSocket message `{"proto":"1","kind":"config_update","payload": <payload>}`. (Used in singleplayer where the client holds the WebSocket.)
4. **Client → server** (multiplayer): `C2SRuntimeConfigPacket` sends the same JSON to the server; server calls `ServerBridgeWebSocketClient.sendConfigUpdate(payload)` so the dedicated server’s bridge connection receives the update.
5. **Bridge** (`server.py`, `kind == "config_update"`): Merges `msg["payload"]` into `runtime_overlay`, calls `refresh_current_runtime()` (base config + overlay), then `policy.apply_runtime_config(current_runtime)` so the live DQN/reward engine gets the new values. No bridge restart.
6. **Persistence**: Bridge calls `save_runtime_overlay(runtime_overlay)` so the next connection (or restart) uses the same overlay.

### File-based hot-reload (same overlay file)

While a peer is connected, the bridge also **polls** `runtime_overrides.yaml` on disk (interval: `bridge.runtime_overlay_poll_interval_s` in `default.yaml`, default 0.5s; env override `AI_AGENT_RUNTIME_OVERLAY_POLL_S`). When the file’s mtime changes, the bridge reloads the YAML, merges it with base config, and calls `policy.apply_runtime_config(current_runtime)` — the same effect as step 5 above, without a `config_update` WebSocket message. Use this when you edit **`Data/runtime_overrides.yaml`** next to a packaged exe or **`shared/Data/runtime_overrides.yaml`** in dev, or when the mod persists the file and you want the running bridge to pick it up without restarting.

**How to verify:** Change **Step penalty** to a more negative value (e.g. -0.01) in the GUI and click **Apply**. Let the agent run for a minute: you should see it move less (higher cost per step). Change **Epsilon** lower (e.g. 0.2) and **Apply**: behavior should become more greedy/exploit-heavy. No need to restart the bridge.

---

## Runtime overlay path and data dir

- **Overlay file**: `shared/Data/runtime_overrides.yaml` in dev; **`Data/runtime_overrides.yaml`** next to the packaged exe when frozen.
- **Resolved in code**: `ai.utils.runtime_paths.data_dir()` (override with env **`AI_AGENT_BRIDGE_DATA`** pointing at that `Data` folder so Python and the mod agree). `RUNTIME_OVERLAY_PATH = dataDir / "runtime_overrides.yaml"`.
- **Optional**: **`AI_AGENT_BRIDGE_PORT`** overrides the WebSocket listen port (for tests and scripted smoke runs).

**Minecraft client (mod UI writes `runtime_overrides.yaml` too):** The game process must know the same `Data` folder as the bridge. CurseForge / Prism often **do not** pass Windows environment variables into Java, so `AI_AGENT_BRIDGE_DATA` may be unset in the mod even when the bridge exe sees the right `Data`. **Recommended:** a **config file** in your Minecraft **instance** (Prism/Curse profile): `config/ai_agent_bridge_data_path.txt` — first non-blank line that is **not** a `#` comment must be the full path to that `Data` folder (so you can add header comments). A starting template ships with the packaged bridge as `Data/ai_agent_bridge_data_path.example.txt` (copy into `config/`, rename to `ai_agent_bridge_data_path.txt`, edit the path line). **Alternative:** JVM arg `-Dai_agent.bridge_data=M:\path\to\bridge\Data`. After Apply, check the game log for `[AI-BOT][ConfigUI] persisted runtime_overrides.yaml -> ...` to confirm the path.
- **project_root**: Derived from `server.py`’s `__file__` as `server_root.parents[3]`, which is intended to be the repo root that contains `ai_agent_project` (or the `ai_agent_project` directory itself when the `ai` package lives at `ai_agent_project/ai`). So `shared/Data` is `project_root/shared/Data`.

---

## Bridge CWD and finding config / Data

- **Config**: Loaded via `ai.utils.config`, which uses `CONF_DIR = SHARED / "config"` with `SHARED` derived from the `ai` package location (e.g. `ai_agent_project/shared`). So `configs/default.yaml` (and env overrides) must be under that shared dir.
- **Data / overlay**: The bridge uses `server.py`’s `project_root` to form `sharedDir` and `dataDir`, so `shared/Data` and `runtime_overrides.yaml` are found without relying on the process CWD.
- **Recommended run**: Start the bridge so that the `ai` package is importable and lives under the project that contains `shared/`. For example, from repo root:  
  `PYTHONPATH=ai_agent_project python -m bridge.server`  
  or from `ai_agent_project`:  
  `python -m bridge.server`  
  (with `ai_agent_project` on `PYTHONPATH` so `ai` resolves to `ai_agent_project/ai`). Then `configs` and `shared/Data` are resolved from the same project root and the overlay is read/written under `shared/Data/`.

---

## Summary

| Item | Location / behavior |
|------|---------------------|
| Overlay path | `shared/Data/runtime_overrides.yaml` (under project root used by server.py) |
| Load overlay | At start of each WebSocket connection (`load_runtime_overlay()`) |
| Save overlay | After every successful `config_update` (`save_runtime_overlay(runtime_overlay)`) |
| Hot-reload | GUI Apply → `config_update` → merge overlay → `policy.apply_runtime_config(current_runtime)` → save overlay |
| File hot-reload | Edit `runtime_overrides.yaml` on disk → poll detects mtime → reload overlay → `apply_runtime_config` |
