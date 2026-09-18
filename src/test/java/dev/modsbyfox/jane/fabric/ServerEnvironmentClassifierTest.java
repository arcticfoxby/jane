package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.ClientSyncDecision;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import org.junit.jupiter.api.Test;

class ServerEnvironmentClassifierTest {
    @Test
    void fabricSideOnlyModsAreExcludedWithoutLookup() {
        assertEquals(ClientSyncDecision.EXCLUDE, ServerEnvironmentClassifier.fromFabric(ModEnvironment.SERVER).orElseThrow());
        assertEquals(ClientSyncDecision.EXCLUDE, ServerEnvironmentClassifier.fromFabric(ModEnvironment.CLIENT).orElseThrow());
        assertTrue(ServerEnvironmentClassifier.fromFabric(ModEnvironment.UNIVERSAL).isEmpty());
    }
}
