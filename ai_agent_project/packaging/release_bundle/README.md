# Minecraft AI Agent — Bridge (this folder)

This package installs **only** the Python **WebSocket bridge** (the AI process that talks to Minecraft).  
It does **not** install the Forge mod. You add the mod to your game separately (see below).

## What you get

Keep **`Install_AI_Bridge.exe`** and **`_internal`** in the **same folder** (do not share only the `.exe`). Zip the **whole** release folder when distributing.

| Item | Purpose |
|------|--------|
| **`Install_AI_Bridge.exe`** | Asks where to install using a **folder dialog** (Tk on all OSes when available; on Linux, **Zenity** or **KDialog** if Tk is missing). Creates a **`bridge`** subfolder, opens it in the file manager, then starts the bridge on Windows (if present). |
| **`_internal\`** | Python runtime and installer dependencies (required next to the `.exe`). |
| **`README.md`** | This file. |

After installation, the bridge lives in **`<folder-you-chose>\bridge\`**. If you cancel the picker, it falls back to:

`%LOCALAPPDATA%\MinecraftAIAgentBridge\bridge\`

| Location | Contents |
|----------|----------|
| **`minecraft_ai_bridge.exe`** | Start this to run the bridge. |
| **`Data\`** | Checkpoints, `episode_state.json`, **`runtime_overrides.yaml`** (hot-reload / control mode), other runtime data. |
| **`logs\`** | Bridge log files (if written next to the exe). |
| **`_internal\`** | Bundled Python and libraries (do not edit). |

Default config is bundled read-only; runtime tuning uses **`Data\runtime_overrides.yaml`** and the in-game config UI when connected.

## Install the bridge

1. Double-click **`Install_AI_Bridge.exe`**.
2. In the **folder picker**, select an **existing** parent folder (create it first if needed, e.g. **`D:\MinecraftAI`** on Windows or **`~/minecraft-ai`** on Linux/macOS). The installer creates **`bridge`** inside it. **Linux:** install **`python3-tk`** for the Tk dialog, or install **zenity** / **kdialog** as a fallback. *(Set **`AI_AGENT_BRIDGE_HOME`** to skip the picker.)*
3. Wait for extraction. Explorer should open the **`bridge`** folder; the installer then starts **`minecraft_ai_bridge.exe`** if it was unpacked.
4. Next time, run **`minecraft_ai_bridge.exe`** from that **`bridge`** folder (or run the installer again to refresh files; your **`Data`** folder is kept when possible).

**Firewall:** allow the bridge if Windows asks — it listens on **TCP 8765** by default (WebSocket).

### If PyTorch fails with `WinError 1114` / `c10.dll` (often when your username has a space)

Paths under `C:\Users\First Last\...` can break PyTorch’s **`c10.dll`** on some PCs.

**Easiest:** When the installer asks for a folder, choose something like **`D:\MinecraftAI`** (create it first in Explorer, no spaces in the path). The game will use **`D:\MinecraftAI\bridge\...`**.

**Alternate:** Set **`AI_AGENT_BRIDGE_HOME`** to `D:\MinecraftAI` before running the installer to **skip** the picker and force that parent folder. Set **`AI_AGENT_BRIDGE_DATA`** (for the mod UI) to **`D:\MinecraftAI\bridge\Data`** (or wherever **`Data`** ended up).

### How to tell it worked

1. The installer window stays open until you press **Enter** (read the `[Installer]` lines for errors).
2. **File Explorer** should open your **`bridge`** folder (under the path you picked or the default under `%LOCALAPPDATA%\MinecraftAIAgentBridge\`).
3. That folder should contain **`minecraft_ai_bridge.exe`** and **`_internal\`** (large folder).
4. The bridge may open its **own** console window and ask for firewall access.

If Explorer did not open, open the **`bridge`** folder you selected in the picker (or `%LOCALAPPDATA%\MinecraftAIAgentBridge\bridge` if you used the default).

## Install the Forge mod (manual)

The installer **does not** copy any `.jar` into Minecraft.

1. Build the mod from the project (`bot/forge`, `gradlew build`) or obtain **`ai_agent_bot-*.jar`** from your team.
2. Put that `.jar` in the **`mods`** folder of your Minecraft instance:
   - **CurseForge / Prism / etc.:** open the instance folder → `mods`.
   - **Default launcher:** `%APPDATA%\.minecraft\mods`.

Match **Minecraft 1.20.1** and **Forge 47.x** to the project.

## CurseForge + overlay path (optional)

If the in-game config screen should read the same **`runtime_overrides.yaml`** as the packaged bridge, set a JVM argument on the Minecraft instance (or environment variable):

- **`-DAI_AGENT_BRIDGE_DATA=`** *full path to the bridge’s **`Data`** folder*  
  Example: `C:\Users\You\AppData\Local\MinecraftAIAgentBridge\bridge\Data`

Or set **`AI_AGENT_BRIDGE_DATA`** to that same path in Windows environment variables.

## Troubleshooting

- **Game won’t connect:** Start the bridge first, then launch Minecraft. Check that nothing else is using port **8765**.
- **Stuck in wrong control mode:** See project docs; single-player + client mod normally uses **PLAYER** mode. Edit **`Data\runtime_overrides.yaml`** or use **Apply** in the mod UI when the WebSocket is connected.
- **`WinError 1114` / `c10.dll` / lots of WebSocket errors:** PyTorch fails while loading native DLLs. The Windows bridge build now intentionally drops PyInstaller's copied **`msvcp140.dll`** and relies on the system **[VC++ Redistributable x64](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist)** instead, because the bundled copy can break Torch `c10.dll` init. If you still see this, try in order: (1) rebuild with **`build_bridge.ps1`** then **`build_bridge_release.ps1`** and reinstall the fresh release, (2) confirm the VC++ redistributable is installed, (3) set **`AI_AGENT_BRIDGE_HOME`** to a path with **no spaces** if your username contains a space, and (4) reinstall so the bridge picks up PATH sanitization for CUDA vs CPU wheels.
- **Repeated connection spam in the console:** The mod **reconnects** after each handler crash. Fixing PyTorch (above) stops the loop.

For source, build scripts, and the mod, use the main project repository.
