package com.example.aiagent.client.gui;

import com.example.aiagent.BotMod;
import com.example.aiagent.BridgeUriResolver;
import com.example.aiagent.client.ClientBridgeHooks;
import com.example.aiagent.client.ForgeWebSocketClient;
import com.example.aiagent.net.BotNet;
import com.example.aiagent.net.C2SRuntimeConfigPacket;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AiBotConfigScreen extends Screen {

    private static final String RUNTIME_OVERLAY_NAME = "runtime_overrides.yaml";

    /**
     * Paths where the Python bridge may persist {@code control_mode} (same file as {@code RUNTIME_OVERLAY_PATH}
     * on the AI side). First match wins; {@code config/ai_agent_bridge_data_path.txt} is tried before JVM/env/dev paths.
     * <p>
     * Packaged bridge (PyInstaller): overlay is {@code Data/runtime_overrides.yaml} next to the .exe.
     * Point the game at that file using either:
     * <ul>
     *   <li>{@code AI_AGENT_RUNTIME_OVERLAY}=full path to {@code runtime_overrides.yaml}, or</li>
     *   <li>{@code AI_AGENT_BRIDGE_DATA}=full path to the bridge {@code Data} folder (same as Python {@code data_dir()}).</li>
     * </ul>
     * JVM: {@code -Dai_agent.runtime_overlay=}file, or {@code -Dai_agent.bridge_data=}Data folder (matches Python).
     * Config file (when env vars are not passed through the launcher): {@code config/ai_agent_bridge_data_path.txt}
     * in the Minecraft instance — first non-blank, non-{@code #} line = full path to the bridge {@code Data} folder
     * (lines starting with {@code #} are comments).
     */
    private static Path bridgeDataRootFromInstanceConfigTxt(Path cfgFile) throws IOException {
        for (String raw : Files.readString(cfgFile, StandardCharsets.UTF_8).split("\\R")) {
            // Strip UTF-8 BOM if the file was saved as "UTF-8 with BOM" (first line would start with U+FEFF).
            String line = raw.replace('\uFEFF', ' ').trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            return Path.of(line);
        }
        return null;
    }

    /**
     * Candidate paths for {@code runtime_overrides.yaml}, **search order**.
     * <p>
     * {@code config/ai_agent_bridge_data_path.txt} is listed **first** when present so packaged installs
     * (CurseForge / Prism) reliably read/write the same {@code Data} folder as the bridge exe, instead of
     * losing to a stale {@code AI_AGENT_BRIDGE_DATA} env var or an accidental hit on {@code shared/Data} from
     * a dev clone on disk.
     */
    private static List<Path> runtimeOverlayCandidatePaths() {
        List<Path> out = new ArrayList<>();
        // 1) Instance config/ai_agent_bridge_data_path.txt (player-facing; should win over env / dev paths)
        try {
            Path cfgLine = FMLPaths.CONFIGDIR.get().resolve("ai_agent_bridge_data_path.txt");
            if (Files.isRegularFile(cfgLine)) {
                Path root = bridgeDataRootFromInstanceConfigTxt(cfgLine);
                if (root != null) {
                    out.add(root.resolve(RUNTIME_OVERLAY_NAME));
                    out.add(root.resolve("Data").resolve(RUNTIME_OVERLAY_NAME));
                }
            }
        } catch (Exception ignored) {
        }
        String prop = System.getProperty("ai_agent.runtime_overlay");
        if (prop != null && !prop.isBlank()) {
            out.add(Path.of(prop.trim()));
        }
        String envFile = System.getenv("AI_AGENT_RUNTIME_OVERLAY");
        if (envFile != null && !envFile.isBlank()) {
            out.add(Path.of(envFile.trim()));
        }
        String envData = System.getenv("AI_AGENT_BRIDGE_DATA");
        if (envData != null && !envData.isBlank()) {
            Path root = Path.of(envData.trim());
            out.add(root.resolve(RUNTIME_OVERLAY_NAME));
            out.add(root.resolve("Data").resolve(RUNTIME_OVERLAY_NAME));
        }
        // JVM: -Dai_agent.bridge_data=M:\MinecraftAI\bridge\Data (same folder Python uses for Data/)
        String bridgeDataProp = System.getProperty("ai_agent.bridge_data");
        if (bridgeDataProp != null && !bridgeDataProp.isBlank()) {
            Path root = Path.of(bridgeDataProp.trim());
            out.add(root.resolve(RUNTIME_OVERLAY_NAME));
            out.add(root.resolve("Data").resolve(RUNTIME_OVERLAY_NAME));
        }
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            out.add(gameDir.resolve("../../../shared/Data/" + RUNTIME_OVERLAY_NAME).normalize());
            out.add(gameDir.resolve("../../shared/Data/" + RUNTIME_OVERLAY_NAME).normalize());
        } catch (Exception ignored) {
        }
        String ud = System.getProperty("user.dir");
        if (ud != null && !ud.isBlank()) {
            Path cwd = Path.of(ud);
            out.add(cwd.resolve("ai_agent_project/shared/Data/" + RUNTIME_OVERLAY_NAME));
            out.add(cwd.resolve("shared/Data/" + RUNTIME_OVERLAY_NAME));
        }
        return out;
    }

    /**
     * Read {@code control_mode} from the persisted runtime overlay (simple top-level YAML line).
     * Returns normalized {@code PLAYER}, {@code SERVER_BOT}, or null if missing/unreadable.
     */
    /** First overlay file on disk that exists (for logging). */
    private static Path findExistingRuntimeOverlayPath() {
        for (Path path : runtimeOverlayCandidatePaths()) {
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    /**
     * Path used to persist the overlay from the UI (same search order as reads; prefers an existing file,
     * then an existing parent directory, else first candidate).
     */
    private static Path resolveRuntimeOverlayWritePath() {
        for (Path path : runtimeOverlayCandidatePaths()) {
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        for (Path path : runtimeOverlayCandidatePaths()) {
            try {
                Path parent = path.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    return path;
                }
            } catch (Exception ignored) {
            }
        }
        List<Path> c = runtimeOverlayCandidatePaths();
        return c.isEmpty() ? null : c.get(0);
    }

    private static String yamlEscapeMapKey(String k) {
        if (k == null || k.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuote = k.indexOf(':') >= 0 || k.indexOf(' ') >= 0 || k.indexOf('#') >= 0
                || k.charAt(0) == '\'' || k.charAt(0) == '"';
        if (!needsQuote) {
            for (int i = 0; i < k.length(); i++) {
                char ch = k.charAt(i);
                if (ch == '{' || ch == '}' || ch == '[' || ch == ']' || ch == ',') {
                    needsQuote = true;
                    break;
                }
            }
        }
        if (needsQuote) {
            return "\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return k;
    }

    private static String yamlDouble(double v) {
        if (!Double.isFinite(v)) {
            return "0.0";
        }
        String s = String.format(Locale.US, "%.10f", v).replaceAll("0*$", "").replaceAll("\\.$", "");
        return s.isEmpty() ? "0" : s;
    }

    /** Next wall-clock ms when {@link #persistRuntimeOverlayFromUi()} should run (debounced slider drags). */
    private long runtimeOverlayPersistScheduledAtMs = 0L;

    /**
     * After {@link #copyFrom(AiBotConfigScreen)}, skip reloading the overlay from disk so in-memory edits
     * (e.g. new mob) are not wiped; then persist once at end of {@link #init()}.
     */
    private boolean skipHydrateFromDiskOnce = false;
    private boolean persistRuntimeOverlayAfterInit = false;

    private void schedulePersistRuntimeOverlay(long delayMs) {
        long when = System.currentTimeMillis() + Math.max(0L, delayMs);
        if (when > runtimeOverlayPersistScheduledAtMs) {
            runtimeOverlayPersistScheduledAtMs = when;
        }
    }

    /**
     * Writes {@code shared/Data/runtime_overrides.yaml} (or {@code ai_agent.runtime_overlay}) to match every
     * field shown in this screen so disk always matches the UI without waiting for Apply or the Python bridge.
     */
    private void persistRuntimeOverlayFromUi() {
        Path path = resolveRuntimeOverlayWritePath();
        if (path == null) {
            return;
        }
        double eps = Mth.clamp(epsilonValue, EPSILON_MIN, EPSILON_MAX);
        double surv = Mth.clamp(survivalRewardValue, SURVIVAL_MIN, SURVIVAL_MAX);
        double stepP = Mth.clamp(stepPenaltyValue, STEP_PENALTY_MIN, STEP_PENALTY_MAX);
        double moveS = Mth.clamp(moveScaleValue, MOVE_SCALE_MIN, MOVE_SCALE_MAX);
        double maxMove = Mth.clamp(maxMoveRewardValue, MAX_MOVE_REWARD_MIN, MAX_MOVE_REWARD_MAX);
        double noProg = Mth.clamp(noProgressPenaltyValue, NO_PROGRESS_PENALTY_MIN, NO_PROGRESS_PENALTY_MAX);
        double front = Mth.clamp(frontClearBonusValue, FRONT_CLEAR_BONUS_MIN, FRONT_CLEAR_BONUS_MAX);
        double item = Mth.clamp(itemPickupRewardValue, ITEM_PICKUP_MIN, ITEM_PICKUP_MAX);
        int maxSteps = Mth.clamp(maxStepsPerEpisodeValue, MAX_STEPS_MIN, MAX_STEPS_MAX);

        StringBuilder sb = new StringBuilder(768);
        sb.append("# Hot-reload overlay — kept in sync with the in-game AI Bot config UI.\n");
        sb.append("control_mode: ").append(selectedMode.label).append('\n');
        sb.append("policy:\n");
        sb.append("  dqn:\n");
        sb.append("    epsilon_start: ").append(yamlDouble(eps)).append('\n');
        sb.append("  reward:\n");
        sb.append("    survival_reward: ").append(yamlDouble(surv)).append('\n');
        sb.append("    step_penalty: ").append(yamlDouble(stepP)).append('\n');
        sb.append("    move_scale: ").append(yamlDouble(moveS)).append('\n');
        sb.append("    max_move_reward: ").append(yamlDouble(maxMove)).append('\n');
        sb.append("    no_progress_penalty: ").append(yamlDouble(noProg)).append('\n');
        sb.append("    front_clear_bonus: ").append(yamlDouble(front)).append('\n');
        sb.append("    item_pickup_reward: ").append(yamlDouble(item)).append('\n');
        sb.append("    max_steps_per_episode: ").append(maxSteps).append('\n');
        if (blockRewards.isEmpty()) {
            sb.append("    blocks: {}\n");
        } else {
            sb.append("    blocks:\n");
            for (Map.Entry<String, Double> e : blockRewards.entrySet()) {
                double vv = Mth.clamp(e.getValue(), CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX);
                sb.append("      ").append(yamlEscapeMapKey(e.getKey())).append(": ").append(yamlDouble(vv)).append('\n');
            }
        }
        if (mobRewards.isEmpty()) {
            sb.append("    mobs: {}\n");
        } else {
            sb.append("    mobs:\n");
            for (Map.Entry<String, Double> e : mobRewards.entrySet()) {
                double vv = Mth.clamp(e.getValue(), CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX);
                sb.append("      ").append(yamlEscapeMapKey(e.getKey())).append(": ").append(yamlDouble(vv)).append('\n');
            }
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[AI-BOT][ConfigUI] persisted runtime_overrides.yaml -> " + path.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[AI-BOT][ConfigUI] persist runtime overlay failed: " + e.getMessage());
        }
        saveRewards();
    }

    private void applyControlModeFromUiSelection() {
        ForgeWebSocketClient.setControlMode(
                selectedMode == Mode.SERVER_BOT
                        ? ForgeWebSocketClient.ControlMode.SERVER_BOT
                        : ForgeWebSocketClient.ControlMode.PLAYER);
    }

    private static String yamlUnquoteKey(String k) {
        if (k == null) {
            return "";
        }
        String t = k.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1).replace("\\\\", "\\").replace("\\\"", "\"");
        }
        return t;
    }

    /**
     * Load scalar rewards, DQN epsilon, and mob/block maps from the overlay file so opening the UI does not
     * replace disk with widget defaults. Ignores {@code control_mode} (handled by {@link #syncModeFromAuthoritativeSource()}).
     */
    private void hydrateUiFromRuntimeOverlayFile() {
        Path path = findExistingRuntimeOverlayPath();
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        String inList = null;
        for (String line0 : lines) {
            int h = line0.indexOf('#');
            String line = h >= 0 ? line0.substring(0, h) : line0;
            line = line.stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            String trim = line.trim();
            if (line.startsWith("      ") && inList != null) {
                int c = trim.indexOf(':');
                if (c > 0) {
                    String key = yamlUnquoteKey(trim.substring(0, c));
                    String vs = trim.substring(c + 1).trim();
                    try {
                        double val = Double.parseDouble(vs);
                        if ("blocks".equals(inList)) {
                            blockRewards.put(key, Mth.clamp(val, CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX));
                        } else {
                            mobRewards.put(key, Mth.clamp(val, CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }
            if (line.startsWith("    ") && !line.startsWith("      ")) {
                inList = null;
                if ("blocks: {}".equals(trim)) {
                    blockRewards.clear();
                    continue;
                }
                if ("mobs: {}".equals(trim)) {
                    mobRewards.clear();
                    continue;
                }
                if ("blocks:".equals(trim)) {
                    blockRewards.clear();
                    inList = "blocks";
                    continue;
                }
                if ("mobs:".equals(trim)) {
                    mobRewards.clear();
                    inList = "mobs";
                    continue;
                }
                try {
                    if (trim.startsWith("epsilon_start:")) {
                        epsilonValue = Mth.clamp(
                                Double.parseDouble(trim.substring("epsilon_start:".length()).trim()),
                                EPSILON_MIN, EPSILON_MAX);
                        continue;
                    }
                    if (trim.startsWith("survival_reward:")) {
                        survivalRewardValue = Mth.clamp(
                                Double.parseDouble(trim.substring("survival_reward:".length()).trim()),
                                SURVIVAL_MIN, SURVIVAL_MAX);
                        continue;
                    }
                    if (trim.startsWith("step_penalty:")) {
                        stepPenaltyValue = Mth.clamp(
                                Double.parseDouble(trim.substring("step_penalty:".length()).trim()),
                                STEP_PENALTY_MIN, STEP_PENALTY_MAX);
                        continue;
                    }
                    if (trim.startsWith("move_scale:")) {
                        moveScaleValue = Mth.clamp(
                                Double.parseDouble(trim.substring("move_scale:".length()).trim()),
                                MOVE_SCALE_MIN, MOVE_SCALE_MAX);
                        continue;
                    }
                    if (trim.startsWith("max_move_reward:")) {
                        maxMoveRewardValue = Mth.clamp(
                                Double.parseDouble(trim.substring("max_move_reward:".length()).trim()),
                                MAX_MOVE_REWARD_MIN, MAX_MOVE_REWARD_MAX);
                        continue;
                    }
                    if (trim.startsWith("no_progress_penalty:")) {
                        noProgressPenaltyValue = Mth.clamp(
                                Double.parseDouble(trim.substring("no_progress_penalty:".length()).trim()),
                                NO_PROGRESS_PENALTY_MIN, NO_PROGRESS_PENALTY_MAX);
                        continue;
                    }
                    if (trim.startsWith("front_clear_bonus:")) {
                        frontClearBonusValue = Mth.clamp(
                                Double.parseDouble(trim.substring("front_clear_bonus:".length()).trim()),
                                FRONT_CLEAR_BONUS_MIN, FRONT_CLEAR_BONUS_MAX);
                        continue;
                    }
                    if (trim.startsWith("item_pickup_reward:")) {
                        itemPickupRewardValue = Mth.clamp(
                                Double.parseDouble(trim.substring("item_pickup_reward:".length()).trim()),
                                ITEM_PICKUP_MIN, ITEM_PICKUP_MAX);
                        continue;
                    }
                    if (trim.startsWith("max_steps_per_episode:")) {
                        maxStepsPerEpisodeValue = Mth.clamp(
                                Integer.parseInt(trim.substring("max_steps_per_episode:".length()).trim().split("\\s")[0]),
                                MAX_STEPS_MIN, MAX_STEPS_MAX);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private static String readControlModeFromRuntimeOverlay() {
        for (Path path : runtimeOverlayCandidatePaths()) {
            if (!Files.isRegularFile(path)) continue;
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    if (!t.startsWith("control_mode:")) continue;
                    String v = t.substring("control_mode:".length()).trim();
                    if (v.length() >= 2
                            && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
                        v = v.substring(1, v.length() - 1);
                    }
                    v = v.trim().replace("-", "_").toUpperCase();
                    if ("SERVER_BOT".equals(v) || "PLAYER".equals(v)) {
                        return v;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Keep UI and {@link ForgeWebSocketClient} aligned with the bridge's saved overlay when the screen opens.
     */
    private void syncModeFromAuthoritativeSource() {
        ForgeWebSocketClient.ControlMode before = ForgeWebSocketClient.getControlMode();
        Path overlayPath = findExistingRuntimeOverlayPath();
        String fromOverlay = readControlModeFromRuntimeOverlay();

        System.out.println("[AI-BOT][DEBUG][ConfigUI] open sync: in-memory ControlMode=" + before
                + " | runtime_overlays.yaml path=" + (overlayPath != null ? overlayPath.toAbsolutePath() : "(none found)")
                + " | parsed control_mode=" + (fromOverlay != null ? fromOverlay : "(absent)"));

        if ("SERVER_BOT".equals(fromOverlay)) {
            this.selectedMode = Mode.SERVER_BOT;
            ForgeWebSocketClient.setControlMode(ForgeWebSocketClient.ControlMode.SERVER_BOT);
            System.out.println("[AI-BOT][DEBUG][ConfigUI] applied mode from file -> SERVER_BOT");
        } else if ("PLAYER".equals(fromOverlay)) {
            // Overlay often lags (e.g. Apply failed: client WS closed, Python not running). Do not
            // force PLAYER from stale yaml while runtime is already SERVER_BOT.
            if (before == ForgeWebSocketClient.ControlMode.SERVER_BOT) {
                this.selectedMode = Mode.SERVER_BOT;
                System.out.println("[AI-BOT][DEBUG][ConfigUI] overlay PLAYER but in-memory SERVER_BOT — keeping SERVER_BOT (persist via Apply with bridge up or edit yaml)");
            } else {
                this.selectedMode = Mode.PLAYER;
                ForgeWebSocketClient.setControlMode(ForgeWebSocketClient.ControlMode.PLAYER);
                System.out.println("[AI-BOT][DEBUG][ConfigUI] applied mode from file -> PLAYER (if you need SERVER_BOT for MP, Apply while connected or edit yaml)");
            }
        } else {
            this.selectedMode = ForgeWebSocketClient.getControlMode() == ForgeWebSocketClient.ControlMode.SERVER_BOT
                    ? Mode.SERVER_BOT
                    : Mode.PLAYER;
            System.out.println("[AI-BOT][DEBUG][ConfigUI] no control_mode in overlay; CycleButton from in-memory -> " + this.selectedMode);
        }
    }

    private final Screen parent;

    /** Default matches client-bridge / PLAYER path; SERVER_BOT is for dedicated-server + overlay. */
    private Mode selectedMode = Mode.PLAYER;

    /**
     * Bridge address (host:port or full ws:// URI) shown/edited in the UI. Applied on the "Apply"
     * button: updates {@link BridgeUriResolver} (and reconnects) for the integrated-SP/PLAYER path,
     * and is sent to the server via {@link C2SRuntimeConfigPacket} for the dedicated-server path.
     */
    private String bridgeAddressValue = BridgeUriResolver.resolve();
    private EditBox bridgeAddressBox;

    // DQN
    private double epsilonValue = 1.0;

    // Reward (generic)
    private double survivalRewardValue = 0.001;
    private double stepPenaltyValue = -0.001;
    private double moveScaleValue = 1.0;
    private double maxMoveRewardValue = 0.1;
    private double noProgressPenaltyValue = -0.02;
    private double frontClearBonusValue = 0.005;
    private double itemPickupRewardValue = 0.05;
    private int maxStepsPerEpisodeValue = 2000;

    /** Custom reward per mob/block. Key = resource location string (e.g. minecraft:zombie). Order preserved. */
    private final Map<String, Double> mobRewards = new LinkedHashMap<>();
    private final Map<String, Double> blockRewards = new LinkedHashMap<>();

    /** When true, show description text below each field. Toggling re-inits layout. */
    private boolean showCaptions = false;

    /** For render: label row indices for mob/block entries (row index into layout). */
    private final List<String> mobLabelIds = new ArrayList<>();
    private final List<Integer> mobLabelRows = new ArrayList<>();
    private final List<String> blockLabelIds = new ArrayList<>();
    private final List<Integer> blockLabelRows = new ArrayList<>();
    private int mobSectionTitleRow = -1;
    private int blockSectionTitleRow = -1;

    /** Total height of scrollable content; set at end of init(). */
    private int contentHeight = 400;
    /** Current scroll offset (0 = top). */
    private int scrollY = 0;
    /** Whether user is dragging the scrollbar thumb. */
    private boolean scrolling = false;

    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLLBAR_MARGIN = 2;
    private static final int CONTENT_LEFT_PAD = 0;
    private static final int TITLE_AREA_BOTTOM = 48;

    /** Min/max for mob and block reward sliders; negative values deter the bot from that mob/block. */
    private static final double CUSTOM_REWARD_MIN = -2.0;
    private static final double CUSTOM_REWARD_MAX = 2.0;
    private static final double CUSTOM_REWARD_DEFAULT_MOB = 0.5;
    private static final double CUSTOM_REWARD_DEFAULT_BLOCK = 0.1;

    // Bounds (aligned with default.yaml / reward_components)
    private static final double EPSILON_MIN = 0.0;
    private static final double EPSILON_MAX = 1.0;
    private static final double SURVIVAL_MIN = 0.0;
    private static final double SURVIVAL_MAX = 0.01;
    private static final double STEP_PENALTY_MIN = -0.01;
    private static final double STEP_PENALTY_MAX = 0.0;
    private static final double MOVE_SCALE_MIN = 0.0;
    private static final double MOVE_SCALE_MAX = 2.0;
    private static final double MAX_MOVE_REWARD_MIN = 0.0;
    private static final double MAX_MOVE_REWARD_MAX = 0.5;
    private static final double NO_PROGRESS_PENALTY_MIN = -0.1;
    private static final double NO_PROGRESS_PENALTY_MAX = 0.0;
    private static final double FRONT_CLEAR_BONUS_MIN = 0.0;
    private static final double FRONT_CLEAR_BONUS_MAX = 0.02;
    private static final double ITEM_PICKUP_MIN = 0.0;
    private static final double ITEM_PICKUP_MAX = 0.2;
    private static final int MAX_STEPS_MIN = 100;
    private static final int MAX_STEPS_MAX = 10_000;

    /** Row height when captions hidden / shown. Re-init when toggling. */
    private static final int ROW_H_COMPACT = 26;
    private static final int ROW_H_WITH_CAPTIONS = 44;

    private static final List<String> CAPTIONS = List.of(
        "Who controls the bot: SERVER_BOT = server-side fake player; PLAYER = this client.",
        "Python bridge address (host:port or ws://host:port). Applied on Apply; works for integrated singleplayer and dedicated servers alike.",
        "Exploration rate (0=always exploit, 1=always explore). Higher = more random actions early in training.",
        "Small reward every step for staying alive. Keeps the agent from standing still forever.",
        "Tiny penalty per step. Encourages the agent to do something useful rather than idle.",
        "Scale for reward from horizontal movement. Distance moved * this value, capped by max move reward.",
        "Cap on reward from movement per step. Prevents reward hacking by running in circles.",
        "Penalty when the agent is stuck (no position change). Encourages escaping stuck states.",
        "Bonus when the block in front is clear (no obstacle). Encourages looking at open space.",
        "Reward when the agent picks up an item. Encourages collection.",
        "Episode ends after this many policy steps (~100 sec at 20 Hz if 2000).",
        "Toggle to show or hide these descriptions for each setting."
    );

    public enum Mode {
        SERVER_BOT("SERVER_BOT"),
        PLAYER("PLAYER");

        public final String label;
        Mode(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    public AiBotConfigScreen(Screen parent) {
        super(Component.literal("AI Agent Bot Settings"));
        this.parent = parent;
        loadSavedRewards();
    }

    private static Path rewardsConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve(BotMod.MODID + "_rewards.json");
    }

    private void loadSavedRewards() {
        Path path = rewardsConfigPath();
        if (!Files.isRegularFile(path)) return;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            com.google.gson.JsonElement root = BotMod.GSON.fromJson(json, com.google.gson.JsonElement.class);
            if (root == null || !root.isJsonObject()) return;
            com.google.gson.JsonObject obj = root.getAsJsonObject();
            mobRewards.clear();
            if (obj.has("mobs") && obj.get("mobs").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : obj.getAsJsonObject("mobs").entrySet()) {
                    if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber())
                        mobRewards.put(e.getKey(), Mth.clamp(e.getValue().getAsDouble(), CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX));
                }
            }
            blockRewards.clear();
            if (obj.has("blocks") && obj.get("blocks").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : obj.getAsJsonObject("blocks").entrySet()) {
                    if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber())
                        blockRewards.put(e.getKey(), Mth.clamp(e.getValue().getAsDouble(), CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX));
                }
            }
        } catch (Exception ignored) { }
    }

    private void saveRewards() {
        JsonObject root = new JsonObject();
        JsonObject mobs = new JsonObject();
        for (Map.Entry<String, Double> e : mobRewards.entrySet())
            mobs.addProperty(e.getKey(), Mth.clamp(e.getValue(), CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX));
        root.add("mobs", mobs);
        JsonObject blocks = new JsonObject();
        for (Map.Entry<String, Double> e : blockRewards.entrySet())
            blocks.addProperty(e.getKey(), Mth.clamp(e.getValue(), CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX));
        root.add("blocks", blocks);
        try {
            Path path = rewardsConfigPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, BotMod.GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }

    private int rowHeight() {
        return showCaptions ? ROW_H_WITH_CAPTIONS : ROW_H_COMPACT;
    }

    private JsonObject buildConfigPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("control_mode", this.selectedMode.label);
        JsonObject policy = new JsonObject();
        JsonObject dqn = new JsonObject();
        dqn.addProperty("epsilon_start", Mth.clamp(epsilonValue, EPSILON_MIN, EPSILON_MAX));
        policy.add("dqn", dqn);
        JsonObject reward = new JsonObject();
        reward.addProperty("survival_reward", Mth.clamp(survivalRewardValue, SURVIVAL_MIN, SURVIVAL_MAX));
        reward.addProperty("step_penalty", Mth.clamp(stepPenaltyValue, STEP_PENALTY_MIN, STEP_PENALTY_MAX));
        reward.addProperty("move_scale", Mth.clamp(moveScaleValue, MOVE_SCALE_MIN, MOVE_SCALE_MAX));
        reward.addProperty("max_move_reward", Mth.clamp(maxMoveRewardValue, MAX_MOVE_REWARD_MIN, MAX_MOVE_REWARD_MAX));
        reward.addProperty("no_progress_penalty", Mth.clamp(noProgressPenaltyValue, NO_PROGRESS_PENALTY_MIN, NO_PROGRESS_PENALTY_MAX));
        reward.addProperty("front_clear_bonus", Mth.clamp(frontClearBonusValue, FRONT_CLEAR_BONUS_MIN, FRONT_CLEAR_BONUS_MAX));
        reward.addProperty("item_pickup_reward", Mth.clamp(itemPickupRewardValue, ITEM_PICKUP_MIN, ITEM_PICKUP_MAX));
        reward.addProperty("max_steps_per_episode", Mth.clamp(maxStepsPerEpisodeValue, MAX_STEPS_MIN, MAX_STEPS_MAX));
        JsonObject blocks = new JsonObject();
        for (Map.Entry<String, Double> e : blockRewards.entrySet()) {
            blocks.addProperty(e.getKey(), Mth.clamp(e.getValue(), CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX));
        }
        reward.add("blocks", blocks);
        JsonObject mobs = new JsonObject();
        for (Map.Entry<String, Double> e : mobRewards.entrySet()) {
            mobs.addProperty(e.getKey(), Mth.clamp(e.getValue(), CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX));
        }
        reward.add("mobs", mobs);
        policy.add("reward", reward);
        payload.add("policy", policy);
        return payload;
    }

    /** Copy state from another screen (used when re-opening to toggle descriptions so layout is fresh). */
    public void copyFrom(AiBotConfigScreen other) {
        this.selectedMode = other.selectedMode;
        this.bridgeAddressValue = other.bridgeAddressBox != null ? other.bridgeAddressBox.getValue() : other.bridgeAddressValue;
        this.epsilonValue = other.epsilonValue;
        this.survivalRewardValue = other.survivalRewardValue;
        this.stepPenaltyValue = other.stepPenaltyValue;
        this.moveScaleValue = other.moveScaleValue;
        this.maxMoveRewardValue = other.maxMoveRewardValue;
        this.noProgressPenaltyValue = other.noProgressPenaltyValue;
        this.frontClearBonusValue = other.frontClearBonusValue;
        this.itemPickupRewardValue = other.itemPickupRewardValue;
        this.maxStepsPerEpisodeValue = other.maxStepsPerEpisodeValue;
        this.showCaptions = other.showCaptions;
        this.mobRewards.clear();
        this.mobRewards.putAll(other.mobRewards);
        this.blockRewards.clear();
        this.blockRewards.putAll(other.blockRewards);
        this.skipHydrateFromDiskOnce = true;
        this.persistRuntimeOverlayAfterInit = true;
    }

    @Override
    protected void init() {
        final int centerX = this.width / 2;
        final int startY = 52;
        final int rowH = rowHeight();
        final int btnW = 220;
        final int btnH = 20;
        final int sliderH = 22;
        final int sliderW = 220;

        if (!skipHydrateFromDiskOnce) {
            hydrateUiFromRuntimeOverlayFile();
        } else {
            skipHydrateFromDiskOnce = false;
        }
        syncModeFromAuthoritativeSource();

        int row = 0;

        // Mode
        this.addRenderableWidget(
            CycleButton.<Mode>builder(m -> Component.literal(m.label))
                .withValues(Mode.values())
                .withInitialValue(this.selectedMode)
                .create(centerX - btnW / 2, startY + row * rowH, btnW, btnH,
                    Component.literal("Mode"),
                    (btn, value) -> {
                        this.selectedMode = value;
                        applyControlModeFromUiSelection();
                        persistRuntimeOverlayFromUi();
                    })
        );
        row++;

        // Bridge address (host:port or ws://...); works for integrated SP (applied directly to this
        // client's connection) and dedicated servers (pushed to the server via C2SRuntimeConfigPacket).
        bridgeAddressBox = new EditBox(this.font, centerX - btnW / 2, startY + row * rowH, btnW, btnH,
                Component.literal("Bridge address"));
        bridgeAddressBox.setMaxLength(128);
        bridgeAddressBox.setValue(bridgeAddressValue);
        bridgeAddressBox.setResponder(v -> bridgeAddressValue = v);
        this.addRenderableWidget(bridgeAddressBox);
        row++;

        // Epsilon
        double epsilonNorm = (epsilonValue - EPSILON_MIN) / (EPSILON_MAX - EPSILON_MIN);
        this.addRenderableWidget(new AbstractSliderButton(
                centerX - btnW / 2, startY + row * rowH,
                sliderW, sliderH,
                Component.literal("Epsilon: " + String.format("%.2f", epsilonValue)),
                Mth.clamp(epsilonNorm, 0.0, 1.0)
        ) {
            @Override
            protected void updateMessage() {
                double v = Mth.lerp(this.value, EPSILON_MIN, EPSILON_MAX);
                epsilonValue = Mth.clamp(v, EPSILON_MIN, EPSILON_MAX);
                setMessage(Component.literal("Epsilon: " + String.format("%.2f", epsilonValue)));
                schedulePersistRuntimeOverlay(150);
            }
            @Override
            protected void applyValue() {
                double v = Mth.lerp(this.value, EPSILON_MIN, EPSILON_MAX);
                epsilonValue = Mth.clamp(v, EPSILON_MIN, EPSILON_MAX);
                schedulePersistRuntimeOverlay(0);
            }
        });
        row++;

        // Survival reward
        addRewardSlider(centerX, startY, row, rowH, btnW, sliderW, sliderH,
            "Survival", survivalRewardValue, SURVIVAL_MIN, SURVIVAL_MAX, "%.4f",
            (v) -> survivalRewardValue = v, () -> survivalRewardValue);
        row++;

        // Step penalty
        addRewardSlider(centerX, startY, row, rowH, btnW, sliderW, sliderH,
            "Step penalty", stepPenaltyValue, STEP_PENALTY_MIN, STEP_PENALTY_MAX, "%.4f",
            (v) -> stepPenaltyValue = v, () -> stepPenaltyValue);
        row++;

        // Move scale
        addRewardSlider(centerX, startY, row, rowH, btnW, sliderW, sliderH,
            "Move scale", moveScaleValue, MOVE_SCALE_MIN, MOVE_SCALE_MAX, "%.2f",
            (v) -> moveScaleValue = v, () -> moveScaleValue);
        row++;

        // Max move reward
        addRewardSlider(centerX, startY, row, rowH, btnW, sliderW, sliderH,
            "Max move reward", maxMoveRewardValue, MAX_MOVE_REWARD_MIN, MAX_MOVE_REWARD_MAX, "%.3f",
            (v) -> maxMoveRewardValue = v, () -> maxMoveRewardValue);
        row++;

        // No progress penalty
        addRewardSlider(centerX, startY, row, rowH, btnW, sliderW, sliderH,
            "No progress penalty", noProgressPenaltyValue, NO_PROGRESS_PENALTY_MIN, NO_PROGRESS_PENALTY_MAX, "%.3f",
            (v) -> noProgressPenaltyValue = v, () -> noProgressPenaltyValue);
        row++;

        // Front clear bonus
        addRewardSlider(centerX, startY, row, rowH, btnW, sliderW, sliderH,
            "Front clear bonus", frontClearBonusValue, FRONT_CLEAR_BONUS_MIN, FRONT_CLEAR_BONUS_MAX, "%.4f",
            (v) -> frontClearBonusValue = v, () -> frontClearBonusValue);
        row++;

        // Item pickup reward
        addRewardSlider(centerX, startY, row, rowH, btnW, sliderW, sliderH,
            "Item pickup reward", itemPickupRewardValue, ITEM_PICKUP_MIN, ITEM_PICKUP_MAX, "%.3f",
            (v) -> itemPickupRewardValue = v, () -> itemPickupRewardValue);
        row++;

        // Max steps per episode (integer slider)
        double maxStepsNorm = (maxStepsPerEpisodeValue - (double) MAX_STEPS_MIN) / (MAX_STEPS_MAX - MAX_STEPS_MIN);
        this.addRenderableWidget(new AbstractSliderButton(
                centerX - btnW / 2, startY + row * rowH,
                sliderW, sliderH,
                Component.literal("Max steps/episode: " + maxStepsPerEpisodeValue),
                Mth.clamp(maxStepsNorm, 0.0, 1.0)
        ) {
            @Override
            protected void updateMessage() {
                int v = (int) Math.round(Mth.lerp(this.value, (double) MAX_STEPS_MIN, (double) MAX_STEPS_MAX));
                maxStepsPerEpisodeValue = Mth.clamp(v, MAX_STEPS_MIN, MAX_STEPS_MAX);
                setMessage(Component.literal("Max steps/episode: " + maxStepsPerEpisodeValue));
                schedulePersistRuntimeOverlay(150);
            }
            @Override
            protected void applyValue() {
                int v = (int) Math.round(Mth.lerp(this.value, (double) MAX_STEPS_MIN, (double) MAX_STEPS_MAX));
                maxStepsPerEpisodeValue = Mth.clamp(v, MAX_STEPS_MIN, MAX_STEPS_MAX);
                schedulePersistRuntimeOverlay(0);
            }
        });
        row++;

        // ---------- Mob Rewards ----------
        mobSectionTitleRow = row;
        mobLabelIds.clear();
        mobLabelRows.clear();
        row++;
        this.addRenderableWidget(
            Button.builder(Component.literal("Add"), btn -> openSelectMob())
                .bounds(centerX - btnW / 2, startY + row * rowH, btnW, btnH).build()
        );
        row++;
        for (String mobId : mobRewards.keySet()) {
            mobLabelIds.add(mobId);
            mobLabelRows.add(row);
            row++;
            double val = mobRewards.getOrDefault(mobId, CUSTOM_REWARD_DEFAULT_MOB);
            addCustomRewardSlider(centerX, startY, row, rowH, btnW, sliderW, sliderH, mobId, val,
                v -> mobRewards.put(mobId, v), () -> mobRewards.getOrDefault(mobId, CUSTOM_REWARD_DEFAULT_MOB));
            row++;
            String idToRemove = mobId;
            this.addRenderableWidget(
                Button.builder(Component.literal("Remove"), b -> removeMob(idToRemove))
                    .bounds(centerX - btnW / 2 + sliderW + 4, startY + row * rowH, 60, btnH).build()
            );
            row++;
        }

        // ---------- Block Rewards ----------
        blockSectionTitleRow = row;
        blockLabelIds.clear();
        blockLabelRows.clear();
        row++;
        this.addRenderableWidget(
            Button.builder(Component.literal("Add"), btn -> openSelectBlock())
                .bounds(centerX - btnW / 2, startY + row * rowH, btnW, btnH).build()
        );
        row++;
        for (String blockId : blockRewards.keySet()) {
            blockLabelIds.add(blockId);
            blockLabelRows.add(row);
            row++;
            double val = blockRewards.getOrDefault(blockId, CUSTOM_REWARD_DEFAULT_BLOCK);
            addCustomRewardSlider(centerX, startY, row, rowH, btnW, sliderW, sliderH, blockId, val,
                v -> blockRewards.put(blockId, v), () -> blockRewards.getOrDefault(blockId, CUSTOM_REWARD_DEFAULT_BLOCK));
            row++;
            String idToRemove = blockId;
            this.addRenderableWidget(
                Button.builder(Component.literal("Remove"), b -> removeBlock(idToRemove))
                    .bounds(centerX - btnW / 2 + sliderW + 4, startY + row * rowH, 60, btnH).build()
            );
            row++;
        }

        // Show descriptions toggle: re-open screen with new layout so widgets don't duplicate
        this.addRenderableWidget(
            CycleButton.booleanBuilder(Component.literal("On"), Component.literal("Off"))
                .withInitialValue(showCaptions)
                .create(centerX - btnW / 2, startY + row * rowH, btnW, btnH,
                    Component.literal("Show descriptions"),
                    (btn, value) -> {
                        if (minecraft == null) return;
                        showCaptions = value;
                        AiBotConfigScreen newScreen = new AiBotConfigScreen(parent);
                        newScreen.copyFrom(this);
                        minecraft.setScreen(newScreen);
                    })
        );
        row++;

        // Apply
        this.addRenderableWidget(
            Button.builder(Component.literal("Apply"), btn -> {
                boolean hasNetwork = this.minecraft != null && this.minecraft.getConnection() != null;
                boolean integratedSp = this.minecraft != null && this.minecraft.hasSingleplayerServer();
                // Integrated SP still has a ClientPacketListener; C2S would hit a null ServerBridgeWebSocketClient
                // (ServerBotHooks only runs on Dist.DEDICATED_SERVER). Use client WS for hot-reload here.
                boolean dedicatedRemoteMp = hasNetwork && !integratedSp;

                // Hot-reload route:
                // - Integrated singleplayer / main menu: client WebSocket -> Python.
                // - Remote dedicated (or LAN client to dedicated): C2S -> server bridge (SERVER_BOT).
                Mode effectiveMode = dedicatedRemoteMp ? Mode.SERVER_BOT : this.selectedMode;

                System.out.println("[AI-BOT][DEBUG][ConfigUI] Apply: hasNetwork=" + hasNetwork
                        + " integratedSP=" + integratedSp
                        + " dedicatedRemoteMp=" + dedicatedRemoteMp
                        + " | cycle selection=" + this.selectedMode
                        + " | effectiveMode=" + effectiveMode);

                persistRuntimeOverlayFromUi();

                // Bridge address: this is a Java-mod-only setting (where the mod's websocket connects
                // TO), not something Python needs told. Integrated SP / main menu / PLAYER-on-MP all
                // route through this client's own connection, so apply + reconnect it directly here.
                // Dedicated servers own their own connection, so the address instead rides along in
                // the C2SRuntimeConfigPacket payload below and is applied server-side.
                String bridgeAddr = bridgeAddressBox != null ? bridgeAddressBox.getValue().trim() : bridgeAddressValue.trim();
                if (!bridgeAddr.isEmpty() && !dedicatedRemoteMp) {
                    String beforeUri = BridgeUriResolver.resolve();
                    BridgeUriResolver.setOverride(bridgeAddr);
                    String afterUri = BridgeUriResolver.resolve();
                    if (!afterUri.equals(beforeUri)) {
                        ClientBridgeHooks.reconnectBridge();
                        System.out.println("[AI-BOT][DEBUG][ConfigUI] bridge address applied locally -> " + afterUri);
                    }
                }

                ForgeWebSocketClient.ControlMode ctrl = effectiveMode == Mode.SERVER_BOT
                        ? ForgeWebSocketClient.ControlMode.SERVER_BOT
                        : ForgeWebSocketClient.ControlMode.PLAYER;
                ForgeWebSocketClient.setControlMode(ctrl);

                Mode prev = this.selectedMode;
                this.selectedMode = effectiveMode;
                JsonObject payload = buildConfigPayload();
                this.selectedMode = prev;

                if (dedicatedRemoteMp) {
                    if (!bridgeAddr.isEmpty()) {
                        payload.addProperty("bridge_uri", bridgeAddr);
                    }
                    System.out.println("[AI-BOT][DEBUG][ConfigUI] sending C2SRuntimeConfigPacket (server will forward to Python); control_mode in payload="
                            + (payload.has("control_mode") ? payload.get("control_mode").getAsString() : "?")
                            + " bridge_uri=" + (bridgeAddr.isEmpty() ? "(unchanged)" : bridgeAddr));
                    BotNet.CHANNEL.sendToServer(new C2SRuntimeConfigPacket(BotMod.GSON.toJson(payload)));
                } else {
                    System.out.println("[AI-BOT][DEBUG][ConfigUI] sending ForgeWebSocketClient.sendConfigUpdate (direct to Python)");
                    ForgeWebSocketClient.sendConfigUpdate(payload);
                }
                btn.setMessage(Component.literal("Applied"));
            }).bounds(centerX - btnW / 2, startY + row * rowH, btnW, btnH).build()
        );
        row++;

        // Done
        this.addRenderableWidget(
            Button.builder(Component.literal("Done"), btn -> this.onClose())
                .bounds(centerX - btnW / 2, startY + row * rowH, btnW, btnH)
                .build()
        );
        row++;
        this.contentHeight = startY + row * rowH + 32;

        if (persistRuntimeOverlayAfterInit) {
            persistRuntimeOverlayAfterInit = false;
            persistRuntimeOverlayFromUi();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (runtimeOverlayPersistScheduledAtMs > 0L && System.currentTimeMillis() >= runtimeOverlayPersistScheduledAtMs) {
            runtimeOverlayPersistScheduledAtMs = 0L;
            persistRuntimeOverlayFromUi();
        }
    }

    @FunctionalInterface
    private interface DoubleSetter { void set(double v); }
    @FunctionalInterface
    private interface DoubleGetter { double get(); }

    private void addRewardSlider(int centerX, int startY, int row, int rowH, int btnW, int sliderW, int sliderH,
                                  String label, double current, double min, double max, String fmt,
                                  DoubleSetter setter, DoubleGetter getter) {
        double norm = (current - min) / (max - min);
        this.addRenderableWidget(new AbstractSliderButton(
                centerX - btnW / 2, startY + row * rowH,
                sliderW, sliderH,
                Component.literal(label + ": " + String.format(fmt, current)),
                Mth.clamp(norm, 0.0, 1.0)
        ) {
            @Override
            protected void updateMessage() {
                double v = Mth.lerp(this.value, min, max);
                setter.set(Mth.clamp(v, min, max));
                setMessage(Component.literal(label + ": " + String.format(fmt, getter.get())));
                schedulePersistRuntimeOverlay(150);
            }
            @Override
            protected void applyValue() {
                double v = Mth.lerp(this.value, min, max);
                setter.set(Mth.clamp(v, min, max));
                schedulePersistRuntimeOverlay(0);
            }
        });
    }

    private void addCustomRewardSlider(int centerX, int startY, int row, int rowH, int btnW, int sliderW, int sliderH,
                                       String id, double current, DoubleSetter setter, DoubleGetter getter) {
        addRewardSlider(centerX, startY, row, rowH, btnW, sliderW, sliderH,
            id, current, CUSTOM_REWARD_MIN, CUSTOM_REWARD_MAX, "%.3f", setter, getter);
    }

    private void openSelectMob() {
        if (minecraft == null) return;
        minecraft.setScreen(new SelectIdScreen(this, "Select Mob",
            ForgeRegistries.ENTITY_TYPES.getValues().stream()
                .map(et -> ForgeRegistries.ENTITY_TYPES.getKey(et).toString())
                .sorted(),
            id -> {
                AiBotConfigScreen next = new AiBotConfigScreen(parent);
                next.copyFrom(this);
                next.mobRewards.put(id, CUSTOM_REWARD_DEFAULT_MOB);
                minecraft.setScreen(next);
            }));
    }

    private void openSelectBlock() {
        if (minecraft == null) return;
        minecraft.setScreen(new SelectIdScreen(this, "Select Block",
            ForgeRegistries.BLOCKS.getValues().stream()
                .map(b -> ForgeRegistries.BLOCKS.getKey(b).toString())
                .sorted(),
            id -> {
                AiBotConfigScreen next = new AiBotConfigScreen(parent);
                next.copyFrom(this);
                next.blockRewards.put(id, CUSTOM_REWARD_DEFAULT_BLOCK);
                minecraft.setScreen(next);
            }));
    }

    private void removeMob(String id) {
        if (minecraft == null) return;
        AiBotConfigScreen next = new AiBotConfigScreen(parent);
        next.copyFrom(this);
        next.mobRewards.remove(id);
        minecraft.setScreen(next);
    }

    private void removeBlock(String id) {
        if (minecraft == null) return;
        AiBotConfigScreen next = new AiBotConfigScreen(parent);
        next.copyFrom(this);
        next.blockRewards.remove(id);
        minecraft.setScreen(next);
    }

    @Override
    public void onClose() {
        runtimeOverlayPersistScheduledAtMs = 0L;
        persistRuntimeOverlayFromUi();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - this.height);
    }

    private boolean isInScrollbarArea(double mouseX, double mouseY) {
        return mouseX >= this.width - SCROLLBAR_WIDTH - SCROLLBAR_MARGIN && mouseX <= this.width;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        scrollY = Mth.clamp(scrollY, 0, maxScroll());
        int contentW = this.width - SCROLLBAR_WIDTH - SCROLLBAR_MARGIN * 2;
        int maxScroll = maxScroll();

        guiGraphics.enableScissor(0, 0, contentW, this.height);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, -scrollY, 0);

        // Base fill so scrolled regions don't show previous-frame artifacts (smearing)
        guiGraphics.fill(0, 0, this.width, contentHeight, 0xF0100010);
        // Stack the standard screen background (dirt texture) so it fills the full content height
        for (int by = 0; by < contentHeight; by += this.height) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, by, 0);
            this.renderBackground(guiGraphics);
            guiGraphics.pose().popPose();
        }
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        guiGraphics.drawCenteredString(
            this.font,
            Component.literal("Configure agent mode, exploration, and reward parameters"),
            this.width / 2,
            30,
            0xA0A0A0
        );

        super.render(guiGraphics, mouseX, mouseY + scrollY, partialTick);

        int startY = 52;
        int rowH = rowHeight();
        int centerX = this.width / 2;
        int btnW = 220;
        int leftX = centerX - btnW / 2;
        if (mobSectionTitleRow >= 0) {
            guiGraphics.drawString(this.font, Component.literal("Mob Rewards"), leftX, startY + mobSectionTitleRow * rowH + 4, 0xFFFF00, false);
        }
        for (int i = 0; i < mobLabelIds.size(); i++) {
            int r = i < mobLabelRows.size() ? mobLabelRows.get(i) : -1;
            if (r >= 0) guiGraphics.drawString(this.font, Component.literal(mobLabelIds.get(i)), leftX, startY + r * rowH + 2, 0xC0C0C0, false);
        }
        if (blockSectionTitleRow >= 0) {
            guiGraphics.drawString(this.font, Component.literal("Block Rewards"), leftX, startY + blockSectionTitleRow * rowH + 4, 0xFFFF00, false);
        }
        for (int i = 0; i < blockLabelIds.size(); i++) {
            int r = i < blockLabelRows.size() ? blockLabelRows.get(i) : -1;
            if (r >= 0) guiGraphics.drawString(this.font, Component.literal(blockLabelIds.get(i)), leftX, startY + r * rowH + 2, 0xC0C0C0, false);
        }

        if (showCaptions) {
            int captionYOffset = 26;
            int captionColor = 0x808080;
            int maxCaptionWidth = this.width - 40;
            int captionLeftX = centerX - maxCaptionWidth / 2;
            for (int i = 0; i < CAPTIONS.size(); i++) {
                List<FormattedCharSequence> lines = this.font.split(Component.literal(CAPTIONS.get(i)), maxCaptionWidth);
                if (!lines.isEmpty()) {
                    int y = startY + i * rowH + captionYOffset;
                    guiGraphics.drawString(this.font, lines.get(0), captionLeftX, y, captionColor, false);
                }
            }
        }

        guiGraphics.pose().popPose();
        guiGraphics.disableScissor();

        if (maxScroll > 0) {
            int trackX = this.width - SCROLLBAR_WIDTH - SCROLLBAR_MARGIN;
            int trackH = this.height;
            int thumbH = Math.max(24, (this.height * this.height) / contentHeight);
            int thumbY = (int) ((double) scrollY / maxScroll * (trackH - thumbH));
            guiGraphics.fill(trackX, 0, trackX + SCROLLBAR_WIDTH, trackH, 0x80000000);
            guiGraphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xC0FFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return false;
        int delta = (int) Math.round(-amount * 24);
        this.scrollY = Mth.clamp(this.scrollY + delta, 0, maxScroll);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && maxScroll() > 0 && isInScrollbarArea(mouseX, mouseY)) {
            scrolling = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY + scrollY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) scrolling = false;
        return super.mouseReleased(mouseX, mouseY + scrollY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrolling && button == 0) {
            int maxScroll = maxScroll();
            int trackH = this.height;
            int thumbH = Math.max(24, (this.height * this.height) / contentHeight);
            double frac = (mouseY - thumbH / 2.0) / (trackH - thumbH);
            scrollY = Mth.clamp((int) Math.round(frac * maxScroll), 0, maxScroll);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY + scrollY, button, dragX, dragY);
    }
}
