# 1.3 Demonstrable autonomous behavior

## Keybinds (exact)

All keybinds require **in-game** focus (player in a world), not in menus.

| Keybind | Effect |
|--------|--------|
| **Ctrl+P** | Toggle **AI on/off**. When **on**: policy (e.g. online DQN) sends move/look/jump/use actions. When **off**: AI is disabled, local keys are released so the player is fully human-controlled. On-screen message: `[AI-BOT] AI ENABLED` / `DISABLED`. |
| **Ctrl+M** | Toggle **control mode** between **PLAYER** and **SERVER_BOT**. On-screen message: `[AI-BOT] Control Mode: PLAYER` or `SERVER_BOT`. |

Implementation: `ClientBridgeHooks` registers `TOGGLE_AI_KEY` (Ctrl+P) and `TOGGLE_MODE_KEY` (Ctrl+M) in `ModBusClient.onRegisterKeyMappings`. In `onClientTick`, `TOGGLE_AI_KEY.consumeClick()` toggles `aiEnabled` and calls `ForgeWebSocketClient.setAiEnabled()`; `TOGGLE_MODE_KEY.consumeClick()` cycles `ControlMode` and calls `ForgeWebSocketClient.setControlMode()`. No flicker: only one mode is active; switching releases local keys when leaving PLAYER.

---

## Visible behavior

- With the **Python bridge** running and an **online DQN** (or scripted) policy, the agent should:
  - **Move**: forward/strafe from `move` payload.
  - **Look**: yaw/pitch from `look` payload.
  - **Jump / interact** when the policy outputs those actions (discrete actions use `await_result` and are applied in order on the server/client).
- If nothing moves: (1) confirm **Ctrl+P** has been pressed so AI is enabled, (2) confirm bridge is connected (e.g. log “Reconnected” or no connection errors), (3) confirm control mode matches the setup (PLAYER for local character, SERVER_BOT for ghost).

---

## Mode clarity and default for demo

| Mode | Who moves | What the audience sees |
|------|-----------|-------------------------|
| **PLAYER** | The **local player** (your character). | Your character moves, looks, jumps under AI. |
| **SERVER_BOT** | A **server-side FakePlayer** (ghost). | A second “ghost” entity moves; your character can stay still. |

- **Default**: On join, `autoSelectControlMode(mc)` sets **PLAYER** in singleplayer and **SERVER_BOT** in multiplayer. So for a **singleplayer demo**, the default is PLAYER and one character is clearly under AI.
- **Stability**: Only one controller is active at a time. In PLAYER mode the client applies actions to the local player; in SERVER_BOT the client does not apply movement locally (keys are released), and the server applies actions to the FakeBot. No double control or flicker by design.

---

## Recommended demo flow

1. Start the **Python bridge** (e.g. from repo root: `.venv38\Scripts\python -m ai.src.app.server` or your run script).
2. Launch **Minecraft** with the Forge mod, open a **singleplayer** world.
3. Wait for connection (e.g. “Reconnected” or no errors); default mode is **PLAYER**.
4. Press **Ctrl+P** to enable AI. The local character should move/look (and optionally jump/use) according to the policy.
5. Press **Ctrl+P** again to disable AI and regain full control.
6. (Optional) In multiplayer, default is **SERVER_BOT**; enable AI with **Ctrl+P** and point the audience at the ghost bot.
