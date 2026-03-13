# Experiment ideas for students

Short, reproducible experiments you can run with the Minecraft AI agent and the in-game config GUI (or config files) to see how parameters change behavior. Use **singleplayer**, **PLAYER** mode, and **Ctrl+P** to enable AI. Open the config screen (see mod keybind) and click **Apply** after changing values so the bridge hot-reloads config without restarting.

---

## 1. Step penalty: “move less”

- **Idea:** Increase the cost of each step so the agent prefers to move less.
- **What to do:** In the config GUI, set **Step penalty** to a more negative value (e.g. **-0.01** or **-0.02**). Click **Apply**. Run for about 5 minutes with AI on.
- **What you should see:** The agent moves less overall; it may stand still or take fewer exploratory steps. If you set it back to a small penalty (e.g. **-0.001**), movement should increase again after **Apply**.

---

## 2. Epsilon: “more greedy” vs “more random”

- **Idea:** Lower epsilon makes the policy use the current Q-values more (exploit); higher epsilon makes it try random actions more (explore).
- **What to do:** Set **Epsilon** in the GUI to **0.2** and **Apply**. Watch behavior for a few minutes. Then set it to **0.8** or **1.0** and **Apply** again.
- **What you should see:** With lower epsilon, behavior looks more consistent and “greedy” (repeats similar actions). With higher epsilon, you see more random-looking moves and exploration.

---

## 3. Observation rate and latency (when client uses config)

- **Idea:** If the client sends observations at a lower rate, the policy gets fewer updates and reaction can feel slower; you may also see different latency in bridge logs.
- **What to do:** In `shared/config/default.yaml`, under `runtime.obs`, set `rate_hz` to **6** (or **4**). Restart the bridge so the new config is loaded. (When the client respects `runtime.obs.rate_hz` from the server, you could change this via a future hello/config path and avoid restart.)
- **What you should see:** Fewer observation messages per second; in bridge metrics or logs you may see different queue depths or latency. The agent may react more slowly to the world.

---

## 4. Reward: move scale and “front clear” bonus

- **Idea:** Increase reward for moving forward or for having clear space in front to bias the agent toward moving or toward avoiding blocks.
- **What to do:** In the GUI, increase **Move scale** (e.g. to **2.0**) and **Apply**; run for a few minutes. Then try increasing **Front clear bonus** (e.g. to **0.02**) and **Apply**.
- **What you should see:** With higher move scale, the agent may move more; with higher front-clear bonus, it may favor orientations or positions where the front is clear.

---

## 5. Max steps per episode

- **Idea:** Shorter episodes mean the agent hits “done” sooner and resets; useful for curriculum or for testing episode boundaries.
- **What to do:** In the GUI, set **Max steps per episode** to **500** and **Apply**. Let the agent run until the episode resets. Then set it back to **2000** (or default) and **Apply**.
- **What you should see:** Episodes end after 500 steps (resets / episode_start events); with 2000, episodes last longer before reset.

---

## Tips

- **Apply** in the GUI sends a `config_update` to the bridge; the overlay is saved to `shared/Data/runtime_overrides.yaml`, so the next time you start the bridge (or reconnect), your last applied values are used.
- For file-based changes (e.g. `rate_hz` in `default.yaml`), restart the bridge. For GUI-applied reward/DQN/control_mode, no restart is needed.
- If nothing seems to change, check that the bridge is connected, AI is on (**Ctrl+P**), and you’re in **PLAYER** mode so the local character is controlled.
