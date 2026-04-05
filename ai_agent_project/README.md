# Minecraft AI Agent (Capstone & Learning Project)

A Python–Minecraft Forge system where an AI agent controls a Minecraft player through a real-time WebSocket bridge, built as an educational capstone for reinforcement learning and systems programming.

## Additional information about the project

This repository contains two core components working together:

- Python asyncio WebSocket bridge: Streams observations from the client (pose, time/biome, inventory, etc.) and sends actions (move, look, jump, interact) on a fixed tick schedule; configuration is YAML-driven and messages are JSON-validated.
- Java Forge client mod:  Hooks into Minecraft input/telemetry and connects to the bridge using a lightweight WebSocket client, exposing a toggle to switch between human and AI control.

The project aims to be a clean, hackable baseline for experiments: start with scripted/navigation policies, then iterate toward RL (e.g., DQN) using the same I/O protocol. It emphasizes clear configs, reproducible “episodes” with reset/teleport logic, and approachable code so students can read, modify, and extend it quickly.

## Installation

### Prerequisites

Make sure the following are installed before proceeding:

- **Git** ≥ 2.30  
- **Python** ≥ 3.10 (3.10–3.12 recommended)  
- **Java Development Kit (JDK)** 17 — required for Forge 1.20+  
- **Gradle** *(optional)* — the Forge MDK includes a Gradle wrapper, so you usually don’t need to install it manually  
- **Minecraft Java Edition** (with the **Forge Launcher**)  
- **Forge MDK** version matching the mod (e.g., `1.20.1`)  
- Compatible OS: **Windows**, **macOS**, or **Linux** (Windows is most tested)

> **Tip:**  
> Activate your Python virtual environment correctly:  
> • **Windows (PowerShell):** `.\venv\Scripts\Activate`  
> • **macOS/Linux:** `source venv/bin/activate`

---

### Add-ons

This section lists the main dependencies and what they do.

#### Python Bridge Dependencies

| Package | Purpose |
|----------|----------|
| `websockets` | Provides the asynchronous WebSocket server used by the Forge mod |
| `PyYAML` | Loads configuration files for policies and runtime parameters |
| `jsonschema` | Validates Observation, Action, and Event JSON payloads |
| `loguru` | Provides structured and colorful logging |
| `numpy` | Handles numeric operations for observations and normalization |
| *(optional)* `torch`, `gymnasium` | Used in later sprints for reinforcement learning experiments |
| *(optional)* `pydantic` | For type-safe models of the JSON messages |

#### ☕ Java Forge Mod Dependencies

| Library | Purpose |
|----------|----------|
| **Minecraft Forge** | Provides hooks into Minecraft for modding |
| `org.java_websocket` | Handles WebSocket communication with the Python bridge |
| `google.gson` | Serializes and deserializes JSON messages between client and server |

> Java dependencies are resolved automatically using the included Gradle build scripts.

---

### Installation Steps

You can copy and paste the commands below directly into your terminal.

#### 1. Clone the repository

```bash
git clone https://github.com/<your-org>/<your-repo>.git
cd <your-repo>
```

#### 2. Set up Python bridge

```bash
python -m venv venv
.\venv\Scripts\Activate
pip install -U pip
pip install websockets PyYAML jsonschema loguru numpy
```

#### 3. Build the forge mod

```bash
cd mod/
./gradlew build
```

### 4. Install the mod for Minecraft

1. Install the correct version of Forge (e.g., 1.20.1).

2. Navigate to your .minecraft/mods/ folder.

3. Copy the built JAR file into that directory.

4. Launch Minecraft using the Forge profile.

### Packaged bridge: keep the Minecraft config UI in sync with `runtime_overrides.yaml`

If you use the **PyInstaller bridge** (`minecraft_ai_bridge.exe`), settings live in **`Data/runtime_overrides.yaml`** next to the exe. The in-game **AI Bot** config screen reads that file when it opens (and can write it when you **Apply**). For those paths to match, the Minecraft **Java** process must know where that `Data` folder is.

**Recommended setup:** In your **Minecraft instance folder** (the profile Prism/Curse uses), create **`config/ai_agent_bridge_data_path.txt`**. The first non-comment line should be the **full path** to the bridge `Data` folder (same folder as `runtime_overrides.yaml`). Lines starting with `#` are ignored, so you can paste short notes above the path.

- Template: [`configs/ai_agent_bridge_data_path.example.txt`](configs/ai_agent_bridge_data_path.example.txt) — after `packaging/build_bridge.ps1`, a copy also appears next to the exe under **`Data/ai_agent_bridge_data_path.example.txt`**.
- Alternatives (if your launcher supports them): JVM arg `-Dai_agent.bridge_data=...` or env `AI_AGENT_BRIDGE_DATA` — see [docs/HOT_RELOAD_AND_PATHS.md](docs/HOT_RELOAD_AND_PATHS.md).

## Functionality

Once the bridge is running and you have joined a world, the mod connects automatically. Control is toggled with the following keybinds (must be in-game, not in menus):

| Keybind | Action |
|--------|--------|
| **Ctrl+P** | Toggle AI control **on/off**. When on, the policy (e.g. DQN) sends actions; when off, no actions are sent and local keys are released. |
| **Ctrl+M** | Toggle **control mode**: **PLAYER** (AI moves the local player) or **SERVER_BOT** (AI moves a server-side “ghost” bot; you see the bot’s state on the client). |

- **Singleplayer**: Default mode is **PLAYER** (AI controls your character). Press **Ctrl+P** to enable AI; you should see the character move/look/jump according to the policy.
- **Multiplayer**: Default mode is **SERVER_BOT** (AI controls a server-side bot; your client shows the ghost). Press **Ctrl+P** to enable AI; the ghost should move.

For a stable demo, use **singleplayer** and leave mode as **PLAYER** so the audience clearly sees the local character moving under AI control.

### Bridge metrics and logs

When the bridge runs with metrics enabled (`bridge.metrics.enabled: true` in config), it writes NDJSON metric rows to a single file. The path in config (e.g. `logs/bridge_metrics.ndjson`) is **resolved relative to `shared/Data`**, so the file is always written to a known location: **`ai_agent_project/shared/Data/logs/bridge_metrics.ndjson`** (for default path `logs/bridge_metrics.ndjson`). Rows include queue sizes, watermarks, obs/act throughput (obs_per_sec, acts_per_sec), tick latency (p50/p90 ms, hz), and connection events (connection_count on each client connect). Run the bridge with `ai_agent_project` on `PYTHONPATH` so it finds `configs` and `shared/Data`; see [docs/HOT_RELOAD_AND_PATHS.md](docs/HOT_RELOAD_AND_PATHS.md).

## Contributing

1. Fork it!
2. Create your feature branch: `git checkout -b my-new-feature`
3. Commit your changes: `git commit -am 'Add some feature'`
4. Push to the branch: `git push origin my-new-feature`
5. Submit a pull request

## Additional Documentation

- **Packaged bridge (Linux / EC2)**: [packaging/release_bundle/README_LINUX.md](packaging/release_bundle/README_LINUX.md) — `packaging/build_all_linux.sh`, headless install, systemd.
- **Keybinds and demo**: [docs/DEMO_AND_CONTROLS.md](docs/DEMO_AND_CONTROLS.md) — exact keybinds (Ctrl+P, Ctrl+M), mode clarity, and recommended demo flow.
- **Hot-reload and paths**: [docs/HOT_RELOAD_AND_PATHS.md](docs/HOT_RELOAD_AND_PATHS.md) — GUI → config_update flow, overlay path (`shared/Data/runtime_overrides.yaml`), and how to run the bridge so it finds config and Data.
- **Experiment ideas**: [docs/EXPERIMENT_IDEAS.md](docs/EXPERIMENT_IDEAS.md) — short experiments (step penalty, epsilon, obs rate, rewards) for students.
- **Config parameters**: [docs/CONFIG_PARAMETERS.md](docs/CONFIG_PARAMETERS.md) — all exposed runtime parameters and where they are used.
- **Evaluation runner**: [docs/EVALUATION_RUNNER.md](docs/EVALUATION_RUNNER.md) — scenario-driven runs, eval.csv, and optional plots (scripts/run_eval.py).
- **Experimental data (conference)**: [docs/EXPERIMENTAL_DATA.md](docs/EXPERIMENTAL_DATA.md) — baseline, parameter sensitivity, and stability runs (scripts/run_experiments.py).
- Project docs: <https://github.com/Strider3039/CAPSTONE-Minecraft-Ai-Agent/tree/Sprint_2/ai_agent_project/docs>

## License
