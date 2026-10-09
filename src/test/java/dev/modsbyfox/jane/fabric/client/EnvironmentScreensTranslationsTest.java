package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnvironmentScreensTranslationsTest {
    private static final List<String> KEYS = List.of(
            "jane.environment.title", "jane.environment.required", "jane.environment.matched",
            "jane.environment.unmet", "jane.environment.extras", "jane.environment.file_error",
            "jane.environment.unsafe_duplicate",
            "jane.environment.sync", "jane.environment.direct", "jane.environment.details",
            "jane.environment.details_title", "jane.environment.unmet_group",
            "jane.environment.matched_group", "jane.environment.extra_group",
            "jane.direct.title", "jane.direct.confirm", "jane.direct.back_sync",
            "jane.direct.explain1", "jane.direct.explain2", "jane.direct.explain3",
            "jane.direct.unmet_group", "jane.direct.extra_group", "jane.direct.required_id",
            "jane.direct.required_state", "jane.direct.extra_id", "jane.direct.extra_unverified",
            "jane.direct.audit_continue", "jane.direct.reconnect_failed");

    @Test void decisionAndRiskTextExistsInBothLanguages() throws Exception {
        JsonObject chinese = language("zh_cn");
        JsonObject english = language("en_us");
        for (String key : KEYS) {
            assertTrue(chinese.has(key), "Missing Chinese translation: " + key);
            assertTrue(english.has(key), "Missing English translation: " + key);
            assertFalse(chinese.get(key).getAsString().isBlank(), key);
            assertFalse(english.get(key).getAsString().isBlank(), key);
        }
        assertEquals(3, KEYS.stream().filter(key -> key.startsWith("jane.direct.explain")).count());
        assertTrue(chinese.get("jane.direct.explain3").getAsString().contains("不代表"));
        assertTrue(english.get("jane.direct.explain3").getAsString().contains("does not verify"));
    }

    private JsonObject language(String code) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("assets/jane/lang/" + code + ".json")) {
            assertNotNull(stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
