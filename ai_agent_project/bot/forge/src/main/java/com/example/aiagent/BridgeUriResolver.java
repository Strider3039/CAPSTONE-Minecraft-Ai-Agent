package com.example.aiagent;

public final class BridgeUriResolver {
    public static final String PROPERTY_NAME = "ai_agent.bridge_uri";
    public static final String ENV_NAME = "AI_AGENT_BRIDGE_URI";
    public static final String DEFAULT_WS_URI = "ws://127.0.0.1:8765";

    private BridgeUriResolver() {
    }

    public static String resolve() {
        return resolve(System.getProperty(PROPERTY_NAME), System.getenv(ENV_NAME), DEFAULT_WS_URI);
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
