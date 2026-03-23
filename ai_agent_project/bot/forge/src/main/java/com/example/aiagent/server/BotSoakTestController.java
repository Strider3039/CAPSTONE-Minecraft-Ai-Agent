package com.example.aiagent.server;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;

/**
 * Runs an automated end-to-end soak on the authoritative server-bot path.
 *
 * The controller relies on the Python bridge for episode orchestration:
 * - request start via eval_control/start_episode
 * - emit episode_end on timeout/death
 * - accept episode_start from the bridge and reset the bot for the next run
 */
public final class BotSoakTestController {

    private final FakeBotManager bots;
    private final ServerBridgeWebSocketClient bridge;

    private boolean active = false;
    private int targetEpisodes = 0;
    private int maxEpisodeTicks = 0;

    private int startedEpisodes = 0;
    private int completedEpisodes = 0;
    private int deathEpisodes = 0;
    private int timeoutEpisodes = 0;

    private long soakStartGameTick = -1L;
    private long lastObservedGameTick = -1L;
    private long currentEpisodeStartTick = -1L;
    private String lastCompletionReason = "none";
    private String lastSummary = "idle";
    private boolean startRequested = false;
    private long lastTimeoutTriggerTick = Long.MIN_VALUE;

    public BotSoakTestController(FakeBotManager bots, ServerBridgeWebSocketClient bridge) {
        this.bots = bots;
        this.bridge = bridge;
    }

    public void start(ServerLevel level, int episodes, int maxEpisodeTicks) {
        if (level == null) return;

        this.active = true;
        this.targetEpisodes = Math.max(1, episodes);
        this.maxEpisodeTicks = Math.max(20, maxEpisodeTicks);
        this.startedEpisodes = 0;
        this.completedEpisodes = 0;
        this.deathEpisodes = 0;
        this.timeoutEpisodes = 0;
        this.soakStartGameTick = level.getGameTime();
        this.lastObservedGameTick = this.soakStartGameTick;
        this.currentEpisodeStartTick = -1L;
        this.lastCompletionReason = "none";
        this.lastSummary = "running";
        this.startRequested = true;
        this.lastTimeoutTriggerTick = Long.MIN_VALUE;

        if (bridge != null) {
            bridge.ensureConnected();

            JsonObject payload = new JsonObject();
            payload.addProperty("control_mode", "SERVER_BOT");
            bridge.sendConfigUpdate(payload);
        }

        if (bots != null) {
            bots.ensureDefaultBot(level.getServer(), level);
        }

        System.out.println("[AI-BOT][SOAK] Started soak: targetEpisodes=" + this.targetEpisodes
                + " maxEpisodeTicks=" + this.maxEpisodeTicks);
    }

    public void stop(String reason) {
        if (!active) {
            lastSummary = "stopped: " + reason;
            return;
        }

        active = false;
        startRequested = false;
        currentEpisodeStartTick = -1L;
        lastSummary = buildSummary(reason);
        System.out.println("[AI-BOT][SOAK] " + lastSummary);
    }

    public boolean isActive() {
        return active;
    }

    public String getStatusLine() {
        if (!active) return "[AI-BOT][SOAK] " + lastSummary;

        return "[AI-BOT][SOAK] running"
                + " started=" + startedEpisodes + "/" + targetEpisodes
                + " completed=" + completedEpisodes
                + " deaths=" + deathEpisodes
                + " timeouts=" + timeoutEpisodes
                + " last_reason=" + lastCompletionReason;
    }

    public void tick(ServerLevel level) {
        if (!active || level == null) return;

        lastObservedGameTick = level.getGameTime();

        if (startRequested && bridge != null && bridge.isConnected()) {
            bridge.sendEvalControlStartEpisode();
            startRequested = false;
        }

        if (currentEpisodeStartTick < 0) return;

        long now = level.getGameTime();
        if (maxEpisodeTicks > 0 && (now - currentEpisodeStartTick) >= maxEpisodeTicks && now != lastTimeoutTriggerTick) {
            lastTimeoutTriggerTick = now;
            onEpisodeFinished("timeout");
            if (bridge != null) {
                bridge.sendEpisodeEnd("timeout");
            }
        }
    }

    public void onEpisodeStarted(ServerLevel level, String reason) {
        if (level == null) return;

        if (!active) {
            System.out.println("[AI-BOT][SOAK] Episode started while soak inactive. reason=" + reason);
            return;
        }

        startedEpisodes++;
        currentEpisodeStartTick = level.getGameTime();
        lastTimeoutTriggerTick = Long.MIN_VALUE;

        System.out.println("[AI-BOT][SOAK] Episode started #" + startedEpisodes
                + " reason=" + reason
                + " gameTick=" + currentEpisodeStartTick);
    }

    public void onEpisodeFinished(String reason) {
        if (!active) return;

        completedEpisodes++;
        currentEpisodeStartTick = -1L;
        lastCompletionReason = reason == null ? "unknown" : reason;

        if ("death".equals(reason)) deathEpisodes++;
        if ("timeout".equals(reason)) timeoutEpisodes++;

        System.out.println("[AI-BOT][SOAK] Episode finished #" + completedEpisodes
                + " reason=" + lastCompletionReason
                + " deaths=" + deathEpisodes
                + " timeouts=" + timeoutEpisodes);

        if (completedEpisodes >= targetEpisodes) {
            stop("completed");
        }
    }

    private String buildSummary(String reason) {
        long elapsedTicks = soakStartGameTick >= 0 && lastObservedGameTick >= 0
                ? Math.max(0L, lastObservedGameTick - soakStartGameTick)
                : 0L;

        return "soak finished"
                + " reason=" + reason
                + " targetEpisodes=" + targetEpisodes
                + " started=" + startedEpisodes
                + " completed=" + completedEpisodes
                + " deaths=" + deathEpisodes
                + " timeouts=" + timeoutEpisodes
                + " last_reason=" + lastCompletionReason
                + " elapsedTicks~=" + elapsedTicks;
    }
}
