# Mock Minecraft Gymnasium Env — DQN Practice Plan (No Code)

## 0. Purpose (Why this first)
- Get fast learning curves **without running Minecraft**.
- Validate **obs/action/reward** design and **DQN stability** in minutes.
- Produce a drop-in interface that later swaps to the real env.

---

## 1. What You’ll Build (Components)

**A. Mock Environment (Gymnasium API)**  
- `reset()` and `step(action)` only.  
- Simulates a player moving in a simple 2D/2.5D world toward a goal.  
- Returns **obs**, **reward**, **done**, **info**.

**B. Observation Spec (small, stable, normalized)**  
- Vector to goal (dx, dz, optional dy).  
- Horizontal distance to goal (scalar).  
- Heading features: sin(yaw), cos(yaw).  
- On-ground flag, speed estimate, stuck flag.  
- Keep total dims ~10–20 for a tiny DQN.

**C. Discrete Action Set (v1)**  
- TurnLeftSmall, TurnRightSmall  
- LookUpSmall, LookDownSmall (optional for 2D)  
- MoveForward, Stop  
- Jump (optional)  
*Single action per step; fixed magnitudes.*

**D. Reward Function (mirrored later in real env)**  
- `+100` on goal reached  
- `+α * (prev_dist − new_dist)` distance progress (α≈1.0 to start)  
- `−0.01` per step (time pressure)  
- `−5` on stuck; `−20` on “death/reset” (if you include hazards)

**E. Episode Rules**  
- Terminate on: reached goal, max_steps, or hard_stuck.  
- Action repeat **k = 3–5** “ticks” to make steps meaningful.

**F. DQN Agent (tiny)**  
- Stateless action selection (ε-greedy).  
- Replay buffer, target network, periodic target updates.  
- Small MLP (1–2 hidden layers) — **no code here**, just plan it.

**G. Metrics/Logging**  
- Episode return, success rate, average steps, ε value, loss.  
- Rolling averages (e.g., last 50 episodes) for quick trend reading.

---

## 2. Mock World Design (Simple, Then Layer Up)

**Base World (v1)**
- Plane with start at (0, 0) and goal at (Gx, Gz).  
- Deterministic movement with small step size.  
- Optional vertical component (dy) for 2.5D feel.

**Dynamics**
- Turning adjusts yaw; forward applies velocity along yaw.  
- Friction/damping to keep speeds stable.  
- Optional random jitter to simulate imperfect control.

**Walls/Obstacles (v2)**
- Axis-aligned rectangles as “walls.”  
- Collision halts forward progress and may set `stuck_flag`.  
- Optional hazard zones (lava): apply negative reward or “death.”

**Noise & Latency (v3)**
- Add small Gaussian noise to movement or observation.  
- Occasional action drop (simulated network hiccup).  
- Useful for testing robustness before real bridge integration.

---

## 3. Observation Details

**Core Features (recommended)**
1. `dx, dz` to goal (clip to range, then normalize by max range).  
2. `horiz_dist` (scalar, normalized to [0,1]).  
3. `sin(yaw), cos(yaw)` (heading without wraparound).  
4. `on_ground` (0/1), `speed_norm` (0..1), `stuck_flag` (0/1).

**Optional Additions**
- `dy_to_goal` if you simulate height.  
- “Front cell” indicator (blocked vs free) for wall awareness.

**Normalization**
- Clip each physical quantity to a reasonable range; scale to [-1,1] or [0,1].  
- Keep the same scaling you will use in Minecraft.

---

## 4. Action Space (Discrete) and Semantics

**Minimum viable set**
- **TurnLeftSmall**: small negative yaw delta  
- **TurnRightSmall**: small positive yaw delta  
- **MoveForward**: set forward velocity to a fixed value  
- **Stop**: zero forward velocity

**Optional**
- **LookUpSmall / LookDownSmall** if you simulate pitch  
- **Jump** if you simulate elevation changes

**Action Repeat**
- Each `step()` applies the chosen action for **k** sub-steps (e.g., k=4) for smoother motion and fewer decisions.

---

## 5. Reward Shaping (Mirror This Later in Real Env)

**Base Terms**
- **Goal bonus**: large positive reward when `horiz_dist < threshold`.  
- **Progress shaping**: `α * (prev_dist − new_dist)` per step.  
- **Step penalty**: small negative each step to encourage efficiency.

**Regularizers**
- **Stuck penalty**: fire when the agent fails to reduce distance for N steps.  
- **Death/hazard penalty**: if you include hazards.

**Tuning Tips**
- Start with α≈1.0, then adjust to balance progress vs speed.  
- Ensure the **goal bonus** is much larger than cumulative progress to avoid endless circling.

---

## 6. Episode Logic

**Reset**
- Randomize goal position within a bounded square/radius.  
- Reset agent pose to (0,0) with yaw facing random or default direction.  
- Clear timers/counters, stuck detector state.

**Step**
- Decode action → update yaw/velocity → simulate k sub-steps.  
- Compute new observation.  
- Compute reward, update `done`.  
- Populate `info` with helpful diagnostics (distance, collisions, stuck).

**Termination Conditions**
- `goal_reached`: distance < threshold.  
- `max_steps`: cap episode length.  
- `hard_stuck`: no progress beyond tolerance for N steps.

---

## 7. DQN Training Plan (Conceptual Only)

**Policy**
- ε-greedy over the discrete action set.  
- ε schedule: 1.0 → 0.1 over ~50k steps (mock can be much faster, tune as needed).

**Replay & Targets**
- Replay buffer (size 50k–200k).  
- Batch size ~64.  
- Discount γ=0.99.  
- Target network update every 500–2000 steps.

**Optimization**
- Small learning rate (e.g., 5e-4).  
- Gradient clipping to avoid explosions.  
- Smooth reward reporting with rolling averages.

**Stability Checks**
- Loss should decrease/stabilize over time.  
- Success rate should increase across seeds and maps.  
- Verify curves across 3 random seeds.

---

## 8. Metrics, Plots, and Success Criteria

**Track per Episode**
- Total reward (return)  
- Steps to goal  
- Success (True/False)  
- Average per-step progress  
- ε value at start/end of episode

**Aggregate Views**
- Moving average (window 50–100) of return and success%  
- Histogram of episode lengths  
- Optional: distance-to-goal vs time (sanity check on progress shaping)

**Exit Criteria for Mock**
- Success rate ≥ 90% on easy maps.  
- Stable returns across seeds (variation tightens with training).  
- No obvious oscillations after convergence.

---

## 9. Parity Checklist (Mock → Real Bridge)

- **Obs parity:** same feature order, ranges, normalization.  
- **Action parity:** same discrete set, same magnitudes for yaw/pitch/move/jump.  
- **Reward parity:** identical constants and thresholds.  
- **Timing parity:** same action repeat **k**; similar step duration.  
- **Episode parity:** same max_steps, stuck logic, and goal threshold.

*This parity minimizes surprises when you swap the mock env out for the real bridge.*

---

## 10. Experiments to Run (In Order)

1. **Easy map, no walls, fixed goal** → verify monotonic learning.  
2. **Random goals** within a square → test generalization.  
3. **Add walls** (simple rectangles) → confirm avoidance emerges (with shaping via stuck penalty).  
4. **Add noise** to actions/observations → check robustness.  
5. **Ablations**: remove step penalty or progress term to see their effect on learning curves.  
6. **Hyper sweeps**: ε-decay rate, α (progress weight), action repeat **k**, target update.

---

## 11. What to Produce at the End

- **Saved checkpoints** for the tiny DQN (by episode/step count).  
- **CSV/TensorBoard logs** with returns, success rate, steps, ε, and loss.  
- **Short report** (bulleted) on:
  - Which reward weights worked best  
  - Which action magnitudes felt natural  
  - Recommended defaults for the real env

---

## 12. Next Step After Mock

- Swap `MockMinecraftEnv` with your **real bridge-backed env**.  
- Keep identical obs/action/reward/episode logic.  
- If desired, add a **Gymnasium wrapper** around the real env to reuse the same trainer and logging.

---

### One-Page Checklist

- [ ] Define obs vector (dims, normalization).  
- [ ] Define discrete action set + magnitudes.  
- [ ] Specify reward constants and stuck rules.  
- [ ] Design base mock world (2D plane), then walls/noise.  
- [ ] Implement `reset()/step()` with action repeat **k**.  
- [ ] Wire tiny DQN (ε-greedy, replay, target updates).  
- [ ] Log metrics; set success criteria.  
- [ ] Run experiments 1→6; choose defaults.  
- [ ] Freeze spec; port to real env with parity.  
- [ ] (Optional) Add Gym wrapper to the real env for training ergonomics.

---

**Outcome:** You’ll have a validated, small DQN that learns reliably in the mock environment, along with tuned obs/action/reward specs and recommended hyperparameters—ready to transfer to your real Minecraft bridge with minimal friction.
