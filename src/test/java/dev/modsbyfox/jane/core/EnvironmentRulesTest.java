package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EnvironmentRulesTest {
    @Test
    void mapsExplicitAndConservativeEnvironments() {
        assertEquals(ClientSyncDecision.SYNC, EnvironmentRules.fromModrinth("client_and_server"));
        for (String value : List.of("server_only", "dedicated_server_only", "server_only_client_optional",
                "client_only", "client_only_server_optional", "client_or_server",
                "client_or_server_prefers_both", "singleplayer_only")) {
            assertEquals(ClientSyncDecision.EXCLUDE, EnvironmentRules.fromModrinth(value), value);
        }
        for (String value : List.of("unknown", "future_environment")) {
            assertEquals(ClientSyncDecision.SYNC_CONSERVATIVE, EnvironmentRules.fromModrinth(value));
        }
        assertEquals(ClientSyncDecision.SYNC_CONSERVATIVE, EnvironmentRules.fromModrinth(null));
        assertTrue(EnvironmentRules.unknownFutureValue("future_environment"));
        assertFalse(EnvironmentRules.unknownFutureValue("unknown"));
    }
}
