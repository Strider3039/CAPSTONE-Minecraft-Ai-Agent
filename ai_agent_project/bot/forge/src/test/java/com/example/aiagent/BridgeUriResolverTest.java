package com.example.aiagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BridgeUriResolverTest {

    @Test
    void resolve_prefersSystemPropertyOverEnv() {
        assertEquals(
                "ws://from-property:8765",
                BridgeUriResolver.resolve("  ws://from-property:8765  ", "ws://from-env:8765", "ws://fallback:8765")
        );
    }

    @Test
    void resolve_usesEnvWhenPropertyMissing() {
        assertEquals(
                "ws://from-env:8765",
                BridgeUriResolver.resolve("   ", " ws://from-env:8765 ", "ws://fallback:8765")
        );
    }

    @Test
    void resolve_fallsBackToDefaultWhenOverridesMissing() {
        assertEquals(
                "ws://fallback:8765",
                BridgeUriResolver.resolve(null, "", " ws://fallback:8765 ")
        );
    }
}
