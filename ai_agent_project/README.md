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

## Functionality

As the steps above were followed, the AI agent should connect to your client the moment you open a world. The human-ai toggle is the "p" key.

## Contributing

1. Fork it!
2. Create your feature branch: `git checkout -b my-new-feature`
3. Commit your changes: `git commit -am 'Add some feature'`
4. Push to the branch: `git push origin my-new-feature`
5. Submit a pull request

## Additional Documentation

<https://github.com/Strider3039/CAPSTONE-Minecraft-Ai-Agent/tree/Sprint_2/ai_agent_project/docs>

## License
