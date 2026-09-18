package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.RequiredEnvironment;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import org.junit.jupiter.api.Test;

class ServerEnvironmentClassifierTest {
    @Test
    void fabricEnvironmentOnlyMakesExplicitSideConclusions() {
        assertEquals(RequiredEnvironment.SERVER_ONLY,
                ServerEnvironmentClassifier.fromFabric(ModEnvironment.SERVER).orElseThrow());
        assertEquals(RequiredEnvironment.CLIENT_OPTIONAL,
                ServerEnvironmentClassifier.fromFabric(ModEnvironment.CLIENT).orElseThrow());
        assertTrue(ServerEnvironmentClassifier.fromFabric(ModEnvironment.UNIVERSAL).isEmpty());
    }
}
