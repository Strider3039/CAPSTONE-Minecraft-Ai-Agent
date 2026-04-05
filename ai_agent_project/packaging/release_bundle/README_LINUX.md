# Minecraft AI Agent Bridge — Linux release

This folder installs the **bridge** (WebSocket + AI process) on **Linux**, e.g. **Ubuntu on EC2**.  
It does **not** install the Forge mod — add the mod to your Minecraft client/server separately.

## Contents

| Item | Purpose |
|------|--------|
| **`install_ai_bridge`** | Extracts the bridge into a **`bridge`** subfolder under a path you choose |
| **`_internal/`** | Required runtime next to `install_ai_bridge` — ship the **whole** directory |
| **`README.md`** | This file |

## Quick install on EC2 / headless Ubuntu

1. Copy this **entire** folder to the server (e.g. zip it on your PC, `scp` or S3, then `unzip`).
2. Install OS packages (Ubuntu 22.04+ example):

   ```bash
   sudo apt update
   sudo apt install -y unzip
   ```

3. Run the installer **non-interactively** (recommended for SSH with no display):

   ```bash
   cd /path/to/Minecraft_AI_Bridge_Release_Linux
   chmod +x install_ai_bridge

   # Parent directory that will contain ./bridge/ (create it first if you want a fixed path)
   export AI_AGENT_BRIDGE_HOME=/home/ubuntu
   export AI_AGENT_INSTALLER_NO_LAUNCH=1
   ./install_ai_bridge
   ```

   Result: bridge files under **`/home/ubuntu/bridge/`**, including:

   - **`minecraft_ai_bridge`** — run this to start the server
   - **`Data/`** — `runtime_overrides.yaml`, checkpoints, etc.

4. Open **TCP 8765** in the EC2 **security group** if Minecraft connects over the internet.

5. Start the bridge (foreground test):

   ```bash
   /home/ubuntu/bridge/minecraft_ai_bridge
   ```

   Or use `screen`, `tmux`, or **systemd** for a long-running service.

**Example systemd unit** (run as `ubuntu`, adjust paths):

```ini
[Unit]
Description=Minecraft AI Bridge
After=network.target

[Service]
Type=simple
WorkingDirectory=/home/ubuntu/bridge
ExecStart=/home/ubuntu/bridge/minecraft_ai_bridge
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Save as `/etc/systemd/system/minecraft-ai-bridge.service`, then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now minecraft-ai-bridge
```

### Environment variables (installer)

| Variable | Effect |
|----------|--------|
| **`AI_AGENT_BRIDGE_HOME`** | Parent folder for install; **`bridge/`** is created inside it. No GUI prompt. |
| **`AI_AGENT_SILENT_INSTALL=1`** | Use the default XDG path (`~/.local/share/MinecraftAIAgentBridge`) without prompts (if you do **not** set `AI_AGENT_BRIDGE_HOME`). |
| **`AI_AGENT_INSTALLER_NO_LAUNCH=1`** | Do not auto-start the bridge after install (recommended on servers). |

### Point Minecraft at this server

On the **Minecraft** side (client or dedicated server JVM args), set:

```text
-Dai_agent.bridge_uri=ws://YOUR_EC2_PUBLIC_IP:8765
```

(`SERVER_BOT` mode: configure the **server** JVM, not each player.)

## Optional: desktop with GUI

If you have a display and want a folder picker, run `./install_ai_bridge` **without** `AI_AGENT_BRIDGE_HOME`.  
Install **`python3-tk`** or use **zenity** / **kdialog** on Linux if Tk is missing.

## Build these artifacts (developers)

On **Ubuntu x86_64**, from the **`ai_agent_project`** directory (Python 3.10+, JDK 17 for the mod build):

```bash
sudo apt install -y python3-pip python3-venv build-essential openjdk-17-jdk zip
# optional venv:
# python3 -m venv .venv && source .venv/bin/activate

chmod +x packaging/build_bridge_linux.sh packaging/build_bridge_release_linux.sh packaging/build_all_linux.sh
./packaging/build_all_linux.sh
```

- **`SKIP_MOD_BUILD=1 ./packaging/build_bridge_linux.sh`** — bridge only (skip Gradle).

Outputs:

- **`packaging/dist/minecraft_ai_bridge/`** — raw PyInstaller onedir
- **`packaging/dist/Minecraft_AI_Bridge_Release_Linux/`** — what you zip for EC2

## Troubleshooting

- **`install_ai_bridge` permission denied** — `chmod +x install_ai_bridge`
- **Nothing listens on 8765** — run `bridge/minecraft_ai_bridge`; check firewall / security group
- **Out of disk** — PyTorch + bundle is large; use an EBS volume with several GB free for build and install

For protocol, overlay file layout, and JVM `bridge_data` for the mod UI, see the main repo **`docs/HOT_RELOAD_AND_PATHS.md`**.
