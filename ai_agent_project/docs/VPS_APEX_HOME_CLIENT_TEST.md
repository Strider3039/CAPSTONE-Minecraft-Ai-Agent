# Distributed test: VPS bridge + Apex server + home client

Use this checklist when the **bridge** runs on your **VPS**, the **Minecraft dedicated server** (with the AI mod) runs on **Apex**, and you play from your **PC**.

## How traffic should flow

```text
Your PC (Forge client + mod)  ----Minecraft game port (e.g. 25565)---->  Apex dedicated server (Forge + mod)
                                                                              |
                                                                              | WebSocket
                                                                              v
                                                                        VPS (bridge :8765)
```

In typical **`SERVER_BOT`** multiplayer, the **dedicated server** opens the WebSocket to the bridge. Your **home client** only needs to connect to Apex like any other player; it does **not** need `-Dai_agent.bridge_uri` pointing at the VPS unless you are doing something special (e.g. local PLAYER mode against a local bridge).

## Before you start

| Piece | Requirement |
|--------|-------------|
| **VPS** | Bridge running; **`configs/default.yaml`** already uses **`bridge.host: 0.0.0.0`** and **`port: 8765`** so it listens on all interfaces. |
| **VPS firewall / cloud** | Inbound **TCP 8765** allowed from the **internet** (or at least from **Apex’s outbound IPs** if you can restrict). |
| **Apex** | Minecraft **1.20.1**, **Forge 47.x**, your **mod JAR** in `mods/`. **JVM arguments** (see below). Outbound TCP to your VPS **:8765** must be allowed (if unsure, ask Apex support). |
| **Your PC** | Same **Forge + mod** version; join the Apex server address as usual. |

## Order of operations

1. **Start the bridge on the VPS** and confirm it is listening:

   ```bash
   ss -tlnp | grep 8765
   ```

2. **Configure Apex server JVM** (dashboard → server → JVM flags / extra arguments), then **start or restart** the Minecraft server.

3. **Start your client** and join the Apex server.

4. In-game, use your normal controls (e.g. **Ctrl+P** / **Ctrl+M** per project docs) once the world is loaded.

## Apex: required JVM argument

Add (replace with your VPS **public IP** or DNS):

```text
-Dai_agent.bridge_uri=ws://YOUR_VPS_PUBLIC_IP:8765
```

Use **`ws://`** unless you have terminated TLS in front of the bridge (the stock bridge speaks plain WebSocket on 8765).

## Quick connectivity checks

**From your PC** (PowerShell) — confirms the VPS accepts TCP on 8765 from your network (not a full WebSocket test, but useful):

```powershell
Test-NetConnection -ComputerName YOUR_VPS_PUBLIC_IP -Port 8765
```

**From the VPS** — bridge process listening:

```bash
curl -v --max-time 3 telnet://127.0.0.1:8765
```

(Expect a TCP connect; the protocol is WebSocket, not HTTP.)

**Apex → VPS** cannot usually be run from your shell; if the server log shows repeated WebSocket connection failures, verify Apex allows **outbound** connections to your VPS IP and port **8765**, and that the VPS security group / `ufw` allows **inbound** from Apex (or temporarily from `0.0.0.0/0` while debugging).

## What to look for in logs

| Where | Good signs |
|-------|------------|
| **VPS / bridge console** | A peer connects; no immediate disconnect loop. |
| **Apex server log** | Lines from the mod indicating **WebSocket** / bridge connection (project uses tags like **`[AI-BOT]`** / server WS). |
| **Your client** | You can join; in **SERVER_BOT** mode the bot entity/state behaves as documented. |

## Common issues

| Symptom | Things to check |
|---------|------------------|
| Server never connects | Wrong IP/port; typo in **`bridge_uri`**; VPS firewall; Apex blocking outbound. |
| Connect then drop | Bridge crash (check VPS logs); version mismatch; multiple conflicting peers (see **`docs/HOT_RELOAD_AND_PATHS.md`** / server warnings about multiple sessions). |
| Client “fine” but no AI | Server-side only controls **SERVER_BOT**; ensure server JVM has **`bridge_uri`**, mod is loaded, and you toggled AI per **DEMO_AND_CONTROLS.md**. |
| `runtime_overrides` out of sync on PC | Expected if the overlay lives on the VPS: the **Minecraft config UI** on your PC reads paths **on your PC** unless you set **`config/ai_agent_bridge_data_path.txt`** or **`-Dai_agent.bridge_data=`** to a path that actually points at that YAML (e.g. network share). For remote bridges, treat the **VPS `Data/runtime_overrides.yaml`** (or **`config_update`**) as source of truth. |

## Related docs

- **Keybinds / modes:** [DEMO_AND_CONTROLS.md](DEMO_AND_CONTROLS.md)  
- **Ports, overlay, hot-reload:** [HOT_RELOAD_AND_PATHS.md](HOT_RELOAD_AND_PATHS.md)  
- **Linux bridge install:** [packaging/dist/README_Linux.md](../packaging/dist/README_Linux.md)
