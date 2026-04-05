# Minecraft AI Agent Bridge

This folder installs the **bridge program** for the Minecraft AI Agent.

The bridge is the part that talks to Minecraft and runs the AI logic.

> **Important**
> - This release folder is **Windows** (`Install_AI_Bridge.exe`). For **Linux / EC2 Ubuntu**, build or obtain **`Minecraft_AI_Bridge_Release_Linux`** using `packaging/build_all_linux.sh` (see `packaging/release_bundle/README_LINUX.md`).
> - This installer does **not** install the Minecraft mod `.jar`.
> - You still need to add the mod to Minecraft separately.

## Table of Contents

1. [What This Folder Is For](#what-this-folder-is-for)
2. [Quick Start](#quick-start)
3. [What Gets Installed](#what-gets-installed)
4. [Change Settings Without Opening Minecraft](#change-settings-without-opening-minecraft)
5. [Install the Minecraft Mod](#install-the-minecraft-mod)
6. [Use a Remote Bridge or VPS](#use-a-remote-bridge-or-vps)
7. [Match the In-Game Config Screen to This Bridge](#match-the-in-game-config-screen-to-this-bridge)
8. [Troubleshooting](#troubleshooting)
9. [For Developers (building this release)](#for-developers-building-this-release)

## What This Folder Is For

Use this folder when you want to install the **AI bridge program** on a Windows computer or server.

**Examples:**

- Run the bridge on your own PC
- Run the bridge on a different computer on your network
- Run the bridge on a Windows VPS

## Quick Start

If you just want the simplest version:

1. Keep **`Install_AI_Bridge.exe`** and **`_internal`** in the **same folder** (zip and share the **whole** folder, not only the `.exe`).
2. Double-click **`Install_AI_Bridge.exe`**.
3. Choose a **parent** folder when asked (the installer creates a **`bridge`** folder inside it). Create the parent folder first if needed (e.g. `D:\MinecraftAI`).
4. Let it finish extracting. File Explorer should open the new **`bridge`** folder; the installer may start **`minecraft_ai_bridge.exe`** once.
5. Next time, run **`minecraft_ai_bridge.exe`** from that **`bridge`** folder.
6. Put the Minecraft mod `.jar` into your instance’s **`mods`** folder.
7. Start Minecraft or your server.

If Windows asks about the firewall, allow the bridge so it can use **port 8765** (default WebSocket port).

**If you cancel the folder picker**, the installer usually falls back to:

```
%LOCALAPPDATA%\MinecraftAIAgentBridge\bridge\
```

Optional: set environment variable **`AI_AGENT_BRIDGE_HOME`** to a parent path before running the installer to **skip** the picker and force that location.

## What Gets Installed

### Installer files (before you run the installer)

| Item | What it does |
|------|-------------|
| `Install_AI_Bridge.exe` | Extracts the bridge into a new **`bridge`** subfolder under the folder you pick |
| `_internal/` | Files the installer needs — **keep it next to** `Install_AI_Bridge.exe` |
| `README.md` | This guide |

### After installation (`...\bridge\`)

| Item | What it is for |
|------|---------------|
| `minecraft_ai_bridge.exe` | Starts the bridge program |
| `Data/` | Stores checkpoints, logs, and **`runtime_overrides.yaml`** (runtime settings) |
| `Data/runtime_overrides.yaml` | Main settings file you can edit in a text editor |
| `Data/ai_agent_bridge_data_path.example.txt` | **Template** for pointing Minecraft at this `Data` folder (see [Match the In-Game Config Screen](#match-the-in-game-config-screen-to-this-bridge)) |
| `_internal/` | Bundled Python and libraries (do not edit) |

Typical install layout:

```
<folder-you-chose>\bridge\
  minecraft_ai_bridge.exe
  Data\
  _internal\
```

## Change Settings Without Opening Minecraft

The bridge uses:

```
bridge\Data\runtime_overrides.yaml
```

You can edit that file while the bridge is running. **Usually you do not need to restart** the bridge: while a client is connected, the bridge **polls** that file and reloads changes within about a second (see project `default.yaml`: `bridge.runtime_overlay_poll_interval_s`). If something still looks stale, restart **`minecraft_ai_bridge.exe`**.

**Useful keys include:**

- `control_mode`
- `policy.dqn.epsilon_start`
- `policy.reward.survival_reward`
- `policy.reward.step_penalty`
- `policy.reward.move_scale`
- `policy.reward.max_move_reward`
- `policy.reward.no_progress_penalty`
- `policy.reward.front_clear_bonus`
- `policy.reward.item_pickup_reward`
- `policy.reward.max_steps_per_episode`
- `policy.reward.blocks`
- `policy.reward.mobs`

## Install the Minecraft Mod

The bridge installer does **not** place the mod into Minecraft.

You need the built mod `.jar` from your team or project build.

Put the `.jar` into your Minecraft **`mods`** folder:

- **CurseForge / Prism / others:** open instance → **`mods`**
- **Default launcher (Windows):**

```
%APPDATA%\.minecraft\mods
```

Use versions that match the project:

- **Minecraft** `1.20.1`
- **Forge** `47.x`

## Use a Remote Bridge or VPS

By default, the mod connects to:

```
ws://127.0.0.1:8765
```

If the bridge runs on another machine, set the WebSocket URL with a **JVM argument** on the Minecraft **client** and/or **dedicated server** (launcher “Java arguments” / “JVM arguments”):

```
-Dai_agent.bridge_uri=ws://your-bridge-host:8765
```

**Note:** The mod resolves the WebSocket URL in order: JVM **`-Dai_agent.bridge_uri=...`**, then environment variable **`AI_AGENT_BRIDGE_URI`**, then the default above. Many launchers only apply JVM args reliably.

**Common examples**

- **Singleplayer / client:** add the JVM arg in your instance’s Java settings.
- **Dedicated server / Apex:** add the same style of JVM argument on the **server** process.

> **Important:** In **`SERVER_BOT`** mode, the **server** owns the bridge connection. Players usually **do not** need to set `bridge_uri` on their game client.

Open **port 8765** (TCP) on the bridge host and any firewall between the game and the bridge.

## Match the In-Game Config Screen to This Bridge

If you edit **`Data\runtime_overrides.yaml`** on disk, the **Minecraft AI Bot config screen** should show the same values when you **open** it again—but only if the game knows **which folder** that file is in.

**Recommended (works when launchers don’t pass environment variables to Java):**

1. Open **`Data/ai_agent_bridge_data_path.example.txt`** inside your installed **`bridge`** folder.
2. In your **Minecraft instance folder** (Prism/Curse profile), go to **`config/`**.
3. Create **`ai_agent_bridge_data_path.txt`** (copy/rename from the example).
4. Add **one line** with the **full path** to this bridge’s **`Data`** folder (lines starting with `#` are ignored, so you can keep short comments above the path).

Example path (yours will differ):

```
C:\Users\You\AppData\Local\MinecraftAIAgentBridge\bridge\Data
```

**Alternatives** (if your launcher applies them to the **Java** process):

- JVM: **`-Dai_agent.bridge_data=`** *full path to the **`Data`** folder*  
  (Use this exact property name — not `AI_AGENT_BRIDGE_DATA` as a `-D` flag.)
- Environment variable: **`AI_AGENT_BRIDGE_DATA`** — only works if the launcher **forwards** it into the game process (many Windows launchers do not).

After **Apply** in the mod UI, check the game log for a line like **`[AI-BOT][ConfigUI] persisted runtime_overrides.yaml -> ...`** to confirm the path.

## Troubleshooting

### Installer finished but unsure if it worked

Check:

1. A **`bridge`** folder exists where you expected (or under `%LOCALAPPDATA%\MinecraftAIAgentBridge\`).
2. It contains **`minecraft_ai_bridge.exe`** and **`_internal\`**.
3. It contains **`Data\`** (and usually **`runtime_overrides.yaml`** after the bridge has run once).

### Game or server won’t connect

1. Start the bridge first.
2. Ensure port **8765** is free and not blocked by firewall.
3. If remote, verify **`ai_agent.bridge_uri`** JVM arg matches `ws://host:8765`.

### Settings changes not applying

1. Save **`runtime_overrides.yaml`**; with the bridge running and connected, wait a moment for file reload.
2. If needed, restart **`minecraft_ai_bridge.exe`**.
3. If the **in-game** screen looks wrong after editing the file, set up **`config/ai_agent_bridge_data_path.txt`** (see above).

### Using Apex or another host

Add to **server** JVM arguments, for example:

```
-Dai_agent.bridge_uri=ws://YOUR_VPS_IP:8765
```

Restart the server and check logs for a successful bridge connection.

### `WinError 1114` or `c10.dll` error

Often worse when install paths have **spaces** (e.g. under `C:\Users\First Last\...`).

**Try:**

1. Install to a short path without spaces, e.g. **`D:\MinecraftAI`**, using the folder picker or **`AI_AGENT_BRIDGE_HOME`**.
2. Install **[Microsoft Visual C++ Redistributable x64](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist)**.

The packaged bridge build may remove a bundled `msvcp140.dll` so PyTorch uses the system runtime; a current build from the repo’s scripts reflects that.

### Bridge spamming connection errors

Check:

- Bridge is running
- Address / **`ai_agent.bridge_uri`** is correct
- Firewall allows **8765**

## Final Notes

This README is for students and teachers who want to run the packaged bridge without reading source code.

## For Developers (building this release)

**Windows:** From **`ai_agent_project`** (Python, JDK 17, PyInstaller):

1. **`packaging\build_bridge.ps1`** — Forge mod + **`packaging\dist\minecraft_ai_bridge\`**
2. **`packaging\build_bridge_release.ps1`** — **`packaging\dist\Minecraft_AI_Bridge_Release\`** (`Install_AI_Bridge.exe`, `_internal\`, this README).

**Linux (e.g. Ubuntu on EC2 for builds):** `chmod +x packaging/*.sh` then **`./packaging/build_all_linux.sh`** → **`packaging/dist/Minecraft_AI_Bridge_Release_Linux/`** (`install_ai_bridge`, `_internal/`, `README.md` from **`README_LINUX.md`**). See **`packaging/release_bundle/README_LINUX.md`**.

Zip the entire release folder to distribute.

For hot-reload paths and advanced options, see the main project **`docs/HOT_RELOAD_AND_PATHS.md`**.
