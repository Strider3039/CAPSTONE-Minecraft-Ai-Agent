# Minecraft AI Agent Bridge

This folder installs the **bridge program** for the Minecraft AI Agent.

The bridge is the part that talks to Minecraft and runs the AI logic.

Important:

- This installer does **not** install the Minecraft mod `.jar`.
- You still need to add the mod to Minecraft separately.

## Table of Contents

1. [What This Folder Is For](#what-this-folder-is-for)
2. [Quick Start](#quick-start)
3. [What Gets Installed](#what-gets-installed)
4. [Change Settings Without Opening Minecraft](#change-settings-without-opening-minecraft)
5. [Install the Minecraft Mod](#install-the-minecraft-mod)
6. [Use a Remote Bridge or VPS](#use-a-remote-bridge-or-vps)
7. [Troubleshooting](#troubleshooting)

## What This Folder Is For

Use this folder when you want to install the **AI bridge program** on a computer or server.

Examples:

- Run the bridge on your own computer.
- Run the bridge on a different computer on your network.
- Run the bridge on a VPS.

## Quick Start

If you just want the simplest version:

1. Double-click **`Install_AI_Bridge.exe`**.
2. Choose a folder when asked.
3. Let it finish installing.
4. Open the new **`bridge`** folder if it does not open by itself.
5. Run **`minecraft_ai_bridge.exe`**.
6. Put the Minecraft mod `.jar` into your Minecraft `mods` folder.
7. Start Minecraft or your server.

If Windows asks about the firewall, allow the bridge so it can use **port 8765**.

## What Gets Installed

Keep **`Install_AI_Bridge.exe`** and **`_internal`** in the same folder before running the installer.

| Item | What it does |
| ---- | ------------ |
| **`Install_AI_Bridge.exe`** | Installs the bridge into a new `bridge` folder. |
| **`_internal\`** | Files the installer needs. Keep it next to the installer. |
| **`README.md`** | This guide. |

After installation, the bridge is usually here:

`<folder-you-chose>\bridge\`

If you cancel the folder picker on Windows, it usually falls back to:

`%LOCALAPPDATA%\MinecraftAIAgentBridge\bridge\`

Inside the installed `bridge` folder, you should see:

| Item | What it is for |
| ---- | -------------- |
| **`minecraft_ai_bridge.exe`** | Starts the bridge program. |
| **`Data\`** | Stores settings and runtime files. |
| **`Data\runtime_overrides.yaml`** | The main settings file you can edit by hand. |
| **`_internal\`** | Program files. Do not edit these. |

## Change Settings Without Opening Minecraft

The installer now creates this file for you:

`bridge\Data\runtime_overrides.yaml`

You can open that file in a text editor and change settings there without opening Minecraft.

Useful settings in that file include:

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

After saving the file, restart **`minecraft_ai_bridge.exe`** to make sure the new settings load.

## Install the Minecraft Mod

The bridge installer does **not** place the mod into Minecraft for you.

You need the built mod `.jar` file from your team or from the project build.

Put the `.jar` into the `mods` folder for your Minecraft installation:

- CurseForge / Prism / other launchers: open the instance folder, then open `mods`
- Default Minecraft launcher on Windows: `%APPDATA%\.minecraft\mods`

Make sure you are using the correct Minecraft and Forge versions for this project:

- Minecraft `1.20.1`
- Forge `47.x`

## Use a Remote Bridge or VPS

By default, the mod looks for the bridge at:

`ws://127.0.0.1:8765`

If your bridge is running on a different machine, like a VPS, you can change the bridge address.

Ways to set the bridge address:

- JVM argument: `-Dai_agent.bridge_uri=ws://your-bridge-host:8765`
- Environment variable: `AI_AGENT_BRIDGE_URI=ws://your-bridge-host:8765`

Common examples:

- **Singleplayer or client-controlled mode:** set the bridge URI in your Minecraft launcher if your client should connect to a remote bridge.
- **Dedicated server or Apex hosting:** set the bridge URI in the server startup JVM arguments so the server-side mod connects to the VPS bridge.

Important:

- In multiplayer **`SERVER_BOT`** mode, the **server** usually owns the bridge connection.
- That means players normally do **not** need to set the bridge URI on their own computers for that mode.

### Optional: Let the Minecraft UI Read the Same Settings File

If you want the in-game config screen to use the same `runtime_overrides.yaml` file as the packaged bridge, set:

- JVM argument: `-DAI_AGENT_BRIDGE_DATA=<full path to the bridge Data folder>`
- Or environment variable: `AI_AGENT_BRIDGE_DATA=<full path to the bridge Data folder>`

Example:

`C:\Users\You\AppData\Local\MinecraftAIAgentBridge\bridge\Data`

## Troubleshooting

### The installer finished, but I do not know if it worked

Check for these signs:

1. A `bridge` folder was created.
2. That folder contains `minecraft_ai_bridge.exe`.
3. That folder also contains `Data`.
4. The file `Data\runtime_overrides.yaml` exists.

### The game or server will not connect

Try these steps:

1. Start the bridge first.
2. Make sure nothing else is already using port `8765`.
3. If you are using a VPS or another computer, make sure the correct bridge URI is set.
4. Make sure the firewall allows traffic on port `8765`.

### I changed the settings file, but nothing happened

Close and restart **`minecraft_ai_bridge.exe`** after editing `runtime_overrides.yaml`.

### I am using Apex or another hosting company

Put this in the server JVM arguments:

```text
-Dai_agent.bridge_uri=ws://YOUR_VPS_IP:8765
```

Then restart the server and look for a message in the console that says it will connect to that address.

### I get a `WinError 1114` or `c10.dll` error

This sometimes happens when the install path contains spaces, especially in usernames.

Try this:

1. Make a simple folder like `D:\MinecraftAI`
2. Reinstall the bridge there
3. Run it again

If that still does not work, make sure the Microsoft Visual C++ Redistributable x64 is installed:

[VC++ Redistributable x64](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist)

### The bridge keeps spamming connection errors

That usually means the bridge cannot start correctly or the game/server is trying to reconnect over and over.

Check:

- the bridge is actually running
- the address is correct
- the firewall is not blocking it

## Final Notes

This README is meant to help students and teachers get started without needing to read the source code.

If you are one of the project developers and need the build scripts or source files, use the main project repository instead.
