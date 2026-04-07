# Minecraft AI Agent Bridge

This folder installs the **bridge program** for the Minecraft AI Agent on **Linux** (e.g. Ubuntu on EC2 or a desktop PC).

The bridge is the part that talks to Minecraft and runs the AI logic.

> **Important**
> - This release folder is **Linux** (`install_ai_bridge` binary). For **Windows**, use **`Minecraft_AI_Bridge_Release`** and **`Install_AI_Bridge.exe`** (see **`packaging/dist/README_Windows.md`** in the repo).
> - This installer does **not** install the Minecraft mod `.jar`.
> - You still need to add the mod to Minecraft separately.

## Table of Contents

1. [What This Folder Is For](#what-this-folder-is-for)
2. [Quick Start](#quick-start)
3. [What Gets Installed](#what-gets-installed)
4. [Change Settings Without Opening Minecraft](#change-settings-without-opening-minecraft)
5. [Install the Minecraft Mod](#install-the-minecraft-mod)
6. [Use a Remote Bridge or VPS (EC2)](#use-a-remote-bridge-or-vps-ec2)
7. [Match the In-Game Config Screen to This Bridge](#match-the-in-game-config-screen-to-this-bridge)
8. [Running the Bridge as a Service (Optional)](#running-the-bridge-as-a-service-optional)
9. [Troubleshooting](#troubleshooting)
10. [For Developers (building this release)](#for-developers-building-this-release)

## What This Folder Is For

Use this folder when you want to install the **AI bridge program** on a Linux computer or cloud VM.

**Examples:**

- Run the bridge on **EC2** or another VPS
- Run the bridge on a home **Ubuntu** machine
- Run the bridge on another host on your network

## Quick Start

If you just want the simplest version:

1. Keep **`install_ai_bridge`** and **`_internal/`** in the **same folder** (zip and share the **whole** directory, not only the binary).
2. Install tools if needed: **`sudo apt install -y unzip`** (Ubuntu/Debian).
3. **`cd /path/to/Minecraft_AI_Bridge_Release_Linux`**
4. **`chmod +x install_ai_bridge`**
5. Run the installer:
   - **With a desktop:** `./install_ai_bridge` and pick a parent folder (a **`bridge`** subfolder is created there).
   - **Headless (SSH / EC2):** set a parent path and skip the GUI:

     ```bash
     export AI_AGENT_BRIDGE_HOME=/home/ubuntu
     export AI_AGENT_INSTALLER_NO_LAUNCH=1
     ./install_ai_bridge
     ```

6. Start the bridge:

   ```bash
   /home/ubuntu/bridge/minecraft_ai_bridge
   ```

   (Adjust the path if you chose a different install parent.)

7. Put the Minecraft mod **`.jar`** into your instance’s **`mods`** folder and use **`-Dai_agent.bridge_uri=...`** if the game is not on the same machine (see below).

**If you cancel the folder picker** (and did not set `AI_AGENT_BRIDGE_HOME`), the installer falls back to the XDG-style default:

```
~/.local/share/MinecraftAIAgentBridge/bridge/
```

(Exact path may vary; see installer log output.)

**Useful installer environment variables**

| Variable | Effect |
|----------|--------|
| **`AI_AGENT_BRIDGE_HOME`** | Parent directory that will contain **`bridge/`** — no GUI prompt. |
| **`AI_AGENT_SILENT_INSTALL=1`** | Install to the default XDG path without prompts (if **`AI_AGENT_BRIDGE_HOME`** is unset). |
| **`AI_AGENT_INSTALLER_NO_LAUNCH=1`** | Do not start the bridge automatically after install (recommended on servers). |

## What Gets Installed

### Installer files (before you run the installer)

| Item | What it does |
|------|-------------|
| `install_ai_bridge` | Extracts the bridge into a new **`bridge`** subfolder under the parent path you choose |
| `_internal/` | Runtime the installer needs — **keep it next to** `install_ai_bridge` |
| `README.md` | This guide (copied from **`packaging/dist/README_Linux.md`** when the release was built) |

### After installation (`.../bridge/`)

| Item | What it is for |
|------|---------------|
| `minecraft_ai_bridge` | Starts the bridge program |
| `Data/` | Checkpoints, logs, and **`runtime_overrides.yaml`** |
| `Data/runtime_overrides.yaml` | Main settings file you can edit in a text editor |
| `Data/ai_agent_bridge_data_path.example.txt` | **Template** for pointing Minecraft at this `Data` folder (see [Match the In-Game Config Screen](#match-the-in-game-config-screen-to-this-bridge)) |
| `_internal/` | Bundled Python and libraries (do not edit) |

Typical install layout:

```
<parent-you-chose>/bridge/
  minecraft_ai_bridge
  Data/
  _internal/
```

## Change Settings Without Opening Minecraft

The bridge reads and writes:

```
bridge/Data/runtime_overrides.yaml
```

You can edit this file while the bridge is running. **Usually you do not need to restart:** while **at least one** Minecraft peer is connected over WebSocket, the bridge **polls** this file and reloads changes within about a second (bundled `configs/default.yaml`: **`bridge.runtime_overlay_poll_interval_s`**, default `0.5`). Environment override: **`AI_AGENT_RUNTIME_OVERLAY_POLL_S`**. If something still looks stale, restart **`minecraft_ai_bridge`**.

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

You need the built mod **`.jar`** from your team or project build.

Put the **`.jar`** into your Minecraft **`mods`** folder:

- **CurseForge / Prism / others:** open instance → **`mods`**
- **Default launcher (Linux):**

```
~/.minecraft/mods
```

Use versions that match the project:

- **Minecraft** `1.20.1`
- **Forge** `47.x`

## Use a Remote Bridge or VPS (EC2)

By default, the mod connects to:

```
ws://127.0.0.1:8765
```

If the bridge runs on another machine (e.g. this Linux host), set the WebSocket URL with a **JVM argument** on the Minecraft **client** and/or **dedicated server**:

```
-Dai_agent.bridge_uri=ws://your-bridge-host:8765
```

**Note:** The mod reads **`ai_agent.bridge_uri`** from the Java process. There is **no** separate environment variable wired in the mod for the URI — use the JVM flag in your launcher or server start script.

**Common examples**

- **Singleplayer / client:** add the JVM arg in your instance’s Java settings.
- **Dedicated server / Apex:** add the same style of JVM argument on the **server** process.

> **Important:** In **`SERVER_BOT`** mode, the **server** owns the bridge connection. Players usually **do not** need to set `bridge_uri` on their game client.

On **EC2**, open **inbound TCP 8765** in the **security group** (and any host firewall, e.g. **`ufw`**) for the IPs that need to reach the bridge.

Example **`ufw`** (if enabled):

```bash
sudo ufw allow 8765/tcp
sudo ufw reload
```

## Match the In-Game Config Screen to This Bridge

If you edit **`Data/runtime_overrides.yaml`** on this machine, the **Minecraft AI Bot config screen** should show the same values when you **open** it again — but only if the **game** knows **which folder** that file is in (often a different PC than the server).

**Recommended (works when launchers don’t pass environment variables to Java):**

1. Open **`Data/ai_agent_bridge_data_path.example.txt`** inside the installed **`bridge`** folder on the machine where **Minecraft runs** (or copy the path line from the server’s `Data` if you share the path via NFS — advanced).
2. In your **Minecraft instance** folder (Prism/Curse profile), open **`config/`**.
3. Create **`ai_agent_bridge_data_path.txt`** (copy/rename from the example).
4. Add **one line** with the **full path** to this bridge’s **`Data`** directory (lines starting with `#` are ignored).

Example (Linux path on the bridge host):

```
/home/ubuntu/bridge/Data
```

**Alternatives** (if your launcher applies them to the **Java** process):

- JVM: **`-Dai_agent.bridge_data=`** *full path to the **`Data`** folder*  
  (Property name is **`ai_agent.bridge_data`**, not `AI_AGENT_BRIDGE_DATA` as a `-D` flag.)
- Environment variable: **`AI_AGENT_BRIDGE_DATA`** — only if the launcher **forwards** it into the game process.

After **Apply** in the mod UI, check the game log for **`[AI-BOT][ConfigUI] persisted runtime_overrides.yaml -> ...`**.

## Running the Bridge as a Service (Optional)

For a long-running **EC2** or server install, use **`systemd`**. Adjust **`User`**, **`Group`**, and paths for your AMI (not every image uses **`ubuntu`**).

Create **`/etc/systemd/system/minecraft-ai-bridge.service`**:

```ini
[Unit]
Description=Minecraft AI Bridge
After=network.target

[Service]
Type=simple
User=ubuntu
Group=ubuntu
WorkingDirectory=/home/ubuntu/bridge
ExecStart=/home/ubuntu/bridge/minecraft_ai_bridge
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now minecraft-ai-bridge
sudo systemctl status minecraft-ai-bridge
```

**Desktop with GUI:** If you run **`./install_ai_bridge`** without **`AI_AGENT_BRIDGE_HOME`**, you may get a folder dialog. Install **`python3-tk`**, or use **zenity** / **kdialog** on Linux if Tk is unavailable.

## Troubleshooting

### Installer finished but unsure if it worked

1. A **`bridge`** folder exists where you expected (or under **`~/.local/share/MinecraftAIAgentBridge/`**).
2. It contains **`minecraft_ai_bridge`** and **`_internal/`**.
3. It contains **`Data/`** (and usually **`runtime_overrides.yaml`** after the bridge has run once).

### `install_ai_bridge` or `minecraft_ai_bridge` permission denied

```bash
chmod +x install_ai_bridge
chmod +x /path/to/bridge/minecraft_ai_bridge
```

### Game or server won’t connect

1. Start the bridge first.
2. Ensure port **8765** is listening: **`ss -tlnp | grep 8765`** (or **`netstat`**).
3. Check **security group** / **ufw** / cloud firewall.
4. If remote, verify **`-Dai_agent.bridge_uri=ws://HOST:8765`** on the correct process (usually the **dedicated server** in **`SERVER_BOT`** mode).

### Settings changes not applying

1. Save **`runtime_overrides.yaml`**; with the bridge running and a client connected, wait a moment for reload.
2. If needed, restart **`minecraft_ai_bridge`** (or **`systemctl restart minecraft-ai-bridge`**).
3. If the **in-game** screen looks wrong after editing the file on the server, configure **`config/ai_agent_bridge_data_path.txt`** or **`-Dai_agent.bridge_data=`** on the **Minecraft** side (see above).

### `externally-managed-environment` / pip errors when building

Use a **venv** under **`ai_agent_project`** and pass it to the build scripts (see [For Developers](#for-developers-building-this-release)).

### `No matching distribution found for rfc3987-syntax`

Use **Python 3.9+** for packaging (3.8 is too old for that dependency).

### Out of disk during build or install

The PyTorch + PyInstaller bundle is **large**; allow **several GB** free on the volume.

### Bridge spamming connection errors

- Bridge is running
- **`ai_agent.bridge_uri`** matches where the bridge actually listens
- Firewall allows **8765**

## Final Notes

This README is for students and teachers who want to run the packaged bridge on Linux without reading the source code.

## For Developers (building this release)

**Linux:** On **Ubuntu x86_64** (or similar), from **`ai_agent_project`**:

**Ubuntu 24.04** example (PEP 668–safe venv):

```bash
sudo apt update
sudo apt install -y python3.12-venv build-essential openjdk-17-jdk zip
cd /path/to/ai_agent_project
python3 -m venv .venv
chmod +x packaging/build_bridge_linux.sh packaging/build_bridge_release_linux.sh packaging/build_all_linux.sh
PYTHON="$(pwd)/.venv/bin/python" ./packaging/build_all_linux.sh
```

- **`SKIP_MOD_BUILD=1`** — skip Gradle if the mod JAR is already built:  
  `SKIP_MOD_BUILD=1 PYTHON="$(pwd)/.venv/bin/python" ./packaging/build_all_linux.sh`

The build scripts also pick **`PYTHON`**, **`VIRTUAL_ENV`**, or **`ai_agent_project/.venv`** automatically when set up as above.

Outputs:

1. **`packaging/build_bridge_linux.sh`** — Forge mod (unless skipped) + **`packaging/dist/minecraft_ai_bridge/`**
2. **`packaging/build_bridge_release_linux.sh`** — **`packaging/dist/Minecraft_AI_Bridge_Release_Linux/`** with **`install_ai_bridge`**, **`_internal/`**, and **`README.md`** copied from **`packaging/dist/README_Linux.md`**.

**Windows:** **`packaging/build_bridge.ps1`** then **`packaging/build_bridge_release.ps1`** — see **`packaging/dist/README_Windows.md`**.

Zip the entire **`Minecraft_AI_Bridge_Release_Linux`** folder to distribute.

For protocol, overlay paths, and JVM options in depth, see **`docs/HOT_RELOAD_AND_PATHS.md`**.
