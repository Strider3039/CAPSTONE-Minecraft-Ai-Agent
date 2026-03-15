package com.example.aiagent;

/**
 * Canonical role and control_mode strings for bridge hello. Must match Python server and
 * shared/schemas/bridge_constants.json exactly.
 */
public final class BridgeConstants {
    private BridgeConstants() {}

    // Roles (who is connecting)
    public static final String ROLE_CLIENT = "client";
    public static final String ROLE_SERVER = "server";

    // Control modes (what mode the bridge expects)
    public static final String MODE_PLAYER = "PLAYER";
    public static final String MODE_SERVER_BOT = "SERVER_BOT";
}
