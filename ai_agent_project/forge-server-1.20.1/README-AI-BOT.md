# Running the AI Bot on This Dedicated Server

The **FakeBot** (AI-controlled NPC) runs entirely on the **dedicated server**. No code separation is required: the same mod JAR contains both client and server code; Forge loads only the server side when you run a dedicated server.

## Architecture (no separation needed)

- **Mod** (`ai_agent_project/bot/forge`):  
  - **Client side** (GUI, observation sender, etc.) runs only when you play in a **game client**.  
  - **Server side** (`FakeBotManager`, `ServerBridgeWebSocketClient`, `ServerBotHooks`) runs only when this directory is used as a **dedicated server**.  
- **Python bridge** (`ai_agent_project/ai`): WebSocket server the **server-side** mod connects to. It runs in a separate process.  
- **This folder** (`forge-server-1.20.1`): A Forge 1.20.1 dedicated server. Once the mod JAR is in `mods/`, the server will connect to the bridge and run the bot.

## What you need to do

### 1. Build the mod

From the repo root (or `ai_agent_project`):

```bash
cd ai_agent_project/bot/forge
./gradlew build
# Windows: gradlew.bat build
```

The built JAR is:

`bot/forge/build/libs/ai_agent_bot-1.0.0.jar`

### 2. Install the mod on this server

Copy that JAR into this server’s `mods/` folder:

```bash
# From ai_agent_project (or repo root)
cp bot/forge/build/libs/ai_agent_bot-1.0.0.jar forge-server-1.20.1/mods/
# Windows: copy bot\forge\build\libs\ai_agent_bot-1.0.0.jar forge-server-1.20.1\mods\
```

So this directory should contain something like:

`forge-server-1.20.1/mods/ai_agent_bot-1.0.0.jar`

(Other mods you already have in `mods/` can stay.)

### 3. Start the Python bridge first

The server-side mod connects **as a client** to the bridge. The bridge must be listening before the server starts.

From `ai_agent_project` (with Python env and deps installed):

```bash
cd ai_agent_project
python -m ai.src.app.server
# Or: python ai/src/app/server.py (with PYTHONPATH set so ai_agent_project is the root)
```

Default: bridge listens on `0.0.0.0:8765` (see `shared/config/default.yaml`).

### 4. Start the dedicated server

From this folder:

```bash
# Windows
run.bat nogui

# Linux/macOS
./run.sh nogui
```

When the **overworld** loads, the mod will:

1. Connect to the bridge at `ws://127.0.0.1:8765`
2. Send `hello` with `role: "server"`
3. Spawn the FakePlayer bot and start sending/receiving actions

You should see log lines like:

- `[AI-BOT] ServerBotHooks registered...`
- `[AI-BOT][SERVER-WS] Will connect to ws://127.0.0.1:8765`
- `[AI-BOT] Dedicated server overworld loaded. Spawning FakePlayer bot...`
- `[AI-BOT][SERVER-WS] Connected to ws://...`

### 5. (Optional) Bridge on another host or port

If the bridge runs on a different machine or port, set the JVM system property when starting the server.

**Option A – in `user_jvm_args.txt`** (in this folder), add a line, e.g.:

```
-Dai_agent.bridge_uri=ws://192.168.1.10:8765
```

**Option B – on the command line:**

```bash
java -Dai_agent.bridge_uri=ws://your-bridge-host:8765 @user_jvm_args.txt @libraries/.../win_args.txt nogui
```

Default if unset: `ws://127.0.0.1:8765`.

## Run order summary

1. Start **Python bridge** (so it listens on 8765).  
2. Start **this dedicated server** (`run.bat nogui` or `./run.sh nogui`).  
3. Optionally join with a **game client** to observe; the bot logic runs on the server.

## Troubleshooting

- **Mod not loading**: Ensure `ai_agent_bot-1.0.0.jar` is in `forge-server-1.20.1/mods/` and Forge version matches (1.20.1, Forge 47.x).
- **No “Connected to” message**: Bridge must be running first; check firewall and that the bridge is bound to the host/port the server uses (e.g. `0.0.0.0:8765` for local).
- **Wrong bridge URL**: Use `-Dai_agent.bridge_uri=ws://host:port` (or `user_jvm_args.txt`) and restart the server.
