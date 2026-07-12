package com.example.aiagent;

import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BridgeUriResolver {
    public static final String PROPERTY_NAME = "ai_agent.bridge_uri";
    public static final String ENV_NAME = "AI_AGENT_BRIDGE_URI";
    public static final String DEFAULT_WS_URI = "ws://127.0.0.1:8765";

    /** Persisted alongside the mod's own config, one instance/machine at a time. */
    private static final String OVERRIDE_FILE_NAME = "ai_agent_bot_bridge_uri.txt";

    /** Set from the in-game config screen; takes priority over the JVM property/env/default. */
    private static volatile String override = null;
    private static volatile boolean overrideLoadedFromDisk = false;

    private BridgeUriResolver() {
    }

    public static String resolve() {
        loadPersistedOverrideOnce();
        String ov = clean(override);
        if (ov != null) {
            return ov;
        }
        return resolve(System.getProperty(PROPERTY_NAME), System.getenv(ENV_NAME), DEFAULT_WS_URI);
    }

    /**
     * Set (and persist to this instance's config dir) the bridge address chosen from the in-game
     * config screen. Accepts a bare {@code host:port} (a {@code ws://} scheme is assumed) or a full
     * {@code ws://}/{@code wss://} URI. Pass null/blank to clear the override and fall back to the
     * property/env/default chain again.
     */
    public static void setOverride(String uriOrHostPort) {
        String normalized = normalize(uriOrHostPort);
        override = normalized;
        overrideLoadedFromDisk = true; // avoid a stale disk read clobbering this in-memory set
        persistOverride(normalized);
    }

    /** Current override, or null if none set (falls back to property/env/default). */
    public static String getOverride() {
        loadPersistedOverrideOnce();
        return override;
    }

    private static String normalize(String raw) {
        String trimmed = clean(raw);
        if (trimmed == null) {
            return null;
        }
        return trimmed.contains("://") ? trimmed : "ws://" + trimmed;
    }

    private static synchronized void loadPersistedOverrideOnce() {
        if (overrideLoadedFromDisk) {
            return;
        }
        overrideLoadedFromDisk = true;
        try {
            Path path = overrideFilePath();
            if (path != null && Files.isRegularFile(path)) {
                override = clean(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static void persistOverride(String normalized) {
        try {
            Path path = overrideFilePath();
            if (path == null) {
                return;
            }
            Files.createDirectories(path.getParent());
            Files.writeString(path, normalized == null ? "" : normalized, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static Path overrideFilePath() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(OVERRIDE_FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    static String resolve(String propertyValue, String envValue, String fallbackValue) {
        String propertyUri = clean(propertyValue);
        if (propertyUri != null) {
            return propertyUri;
        }

        String envUri = clean(envValue);
        if (envUri != null) {
            return envUri;
        }

        String fallbackUri = clean(fallbackValue);
        return fallbackUri != null ? fallbackUri : DEFAULT_WS_URI;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
