# 1.2 Perception–decision–action loop verification

## Observation flow

- **Client → bridge**: Client (`ClientBridgeHooks.onClientTick`) sends observations when `aiEnabled && wsClient.isOpen()` and `now - lastSendMs >= 100` (fixed **100 ms** ≈ 10 Hz). Config has `streaming.target_hz: 12` and `runtime.obs.rate_hz: 12`; client does not read these yet.
- **Bridge**: On `kind == "observation"`, server validates `msg` against `OBS` schema; on failure logs and **skips** (`continue`). Valid messages are enqueued via `EnqueueObservation(obsQueue, msg, ...)`.
- **PolicyWorker**: Each tick calls `DrainLatest()` (drains `obs_q` with `get_nowait()`, keeps only the latest), then `policy_step(latestObs)`.

**Status**: Verified. Optional improvement: make client observation rate configurable (e.g. from config or a constant aligned with `obs.rate_hz`).

---

## Action flow

- **PolicyWorker**: Produces action message → `normalize_action()` → optional schema validate → `QueueAdd(act_q, action_msg, ...)`.
- **Bridge**: `ActionSenderLoop` drains `actQueue` (coalesces non-ACK controls, preserves ACK actions), then `SendAction(item)` sends over WebSocket.
- **SERVER_BOT**: `ServerBridgeWebSocketClient` receives messages → parses action → `actionQueue.offer(step)`. Each server tick, `ServerBotHooks` calls `ws.drainActionsAndApply(level, bots)` → `actionQueue.poll()` → `bots.enqueueActionJson(...)` → `FakeBotManager.pendingActionJson`. `FakeBotManager.tick()` → `drainActions()` → `pendingActionJson.poll()` → parse → `pendingSteps.offer(StepRequest)`. Step state machine: when `!stepActive`, `pendingSteps.poll()` → apply step for `ticks`; when step finishes, emit observation + `action_result`, then take next step.

**Status**: Verified. FIFO from bridge to `actionQueue` to `pendingActionJson` to `pendingSteps`. Under load (`pendingSteps.size() > 200`), oldest step is dropped to bound latency; order is otherwise preserved.

---

## Synchronization (discrete actions / await_result)

- **PolicyWorker**: `action_needs_ack(action_msg)` is true for payload keys `attack`, `use`, `place`, `select_slot`. Those actions get `await_result: true`.
- **Bridge**: `SendAction(..., wait_for_result=True)` creates a future `pending[seq]`, sends the action, then `await asyncio.wait_for(fut, timeout=...)`. On timeout, `actState["actionTimeouts"]` is incremented and the future is removed.
- **Client/Server**: When the step completes, `FakeBotManager` builds `action_result` with same `seq` and `action_id` and offers to `completedStepResults`. `ServerBridgeWebSocketClient.drainCompletedResultsAndSend(bots)` sends it to the bridge.
- **Bridge**: On `kind == "action_result"`, validates with EVT, resolves `fut = pending.pop(seq); fut.set_result(res)`.

**Status**: Verified. Discrete actions wait for `action_result`; bridge and FakeBotManager preserve seq and resolve the correct future.

---

## Schema validation

- **Observations**: `server.Handle` validates with `OBS`; on `ValidationError` logs `"obs failed schema"` and **continues** (message not enqueued).
- **Actions**: `PolicyWorker` uses `runtime_cfg.get("validate_actions", True)`; validates with `act_schema`; on failure logs and **continues** (no action sent). `default.yaml` has `validate_actions: true`.
- **Events** (e.g. `action_result`): Validated with `EVT`; on failure log and continue.

**Status**: Verified. Malformed messages do not crash the loop; they are logged and skipped.
