package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.JaneVersion;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JaneVersionTest {
    @Test void runtimeLogVersionMatchesFabricMetadata() throws Exception {
        try (var stream = JaneVersionTest.class.getResourceAsStream("/fabric.mod.json")) {
            assertTrue(stream != null);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals(JaneVersion.VALUE, json.get("version").getAsString());
            assertEquals("jane", json.get("id").getAsString());
        }
        assertTrue(JaneLog.server().startsWith("[Jane " + JaneVersion.VALUE + "][SERVER]"));
        assertTrue(JaneLog.clientStartup().startsWith("[Jane " + JaneVersion.VALUE + "][CLIENT]"));
    }
}
