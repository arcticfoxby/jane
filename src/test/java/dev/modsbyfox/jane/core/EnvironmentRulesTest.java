package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentRulesTest {
    @Test
    void modrinthMappingIsConservative() {
        Map<String, RequiredEnvironment> cases = Map.ofEntries(
                Map.entry("client_and_server", RequiredEnvironment.CLIENT_REQUIRED),
                Map.entry("server_only", RequiredEnvironment.SERVER_ONLY),
                Map.entry("dedicated_server_only", RequiredEnvironment.SERVER_ONLY),
                Map.entry("server_only_client_optional", RequiredEnvironment.CLIENT_OPTIONAL),
                Map.entry("client_only", RequiredEnvironment.CLIENT_OPTIONAL),
                Map.entry("client_only_server_optional", RequiredEnvironment.CLIENT_OPTIONAL),
                Map.entry("client_or_server", RequiredEnvironment.CLIENT_OPTIONAL),
                Map.entry("client_or_server_prefers_both", RequiredEnvironment.CLIENT_OPTIONAL),
                Map.entry("singleplayer_only", RequiredEnvironment.UNKNOWN),
                Map.entry("unknown", RequiredEnvironment.UNKNOWN),
                Map.entry("future_environment", RequiredEnvironment.UNKNOWN));
        cases.forEach((value, expected) -> assertEquals(expected, EnvironmentRules.fromModrinth(value), value));
        assertEquals(RequiredEnvironment.UNKNOWN, EnvironmentRules.fromModrinth(null));
    }

    @Test
    void overrideOnlyPromotesUnknown() throws Exception {
        assertEquals(RequiredEnvironment.CLIENT_REQUIRED,
                EnvironmentRules.applyOverride("private_mod", RequiredEnvironment.UNKNOWN, true));
        assertThrows(IOException.class, () -> EnvironmentRules.applyOverride("private_mod", RequiredEnvironment.UNKNOWN, false));
        for (RequiredEnvironment automatic : new RequiredEnvironment[]{RequiredEnvironment.SERVER_ONLY,
                RequiredEnvironment.CLIENT_OPTIONAL, RequiredEnvironment.CLIENT_REQUIRED}) {
            assertThrows(IOException.class, () -> EnvironmentRules.applyOverride("mod", automatic, true));
            assertEquals(automatic, EnvironmentRules.applyOverride("mod", automatic, false));
        }
    }
}
