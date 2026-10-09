package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SyncSelectionTranslationsTest {
    private JsonObject language(String locale) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("assets/jane/lang/" + locale + ".json")) {
            assertNotNull(input);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void selectionAndRiskCopyExistsInBothLanguages() throws Exception {
        JsonObject zh = language("zh_cn");
        JsonObject en = language("en_us");
        for (String key : zh.keySet())
            if (key.startsWith("jane.select.") || key.startsWith("jane.risk."))
                assertTrue(en.has(key), "Missing English key: " + key);
        for (String key : en.keySet())
            if (key.startsWith("jane.select.") || key.startsWith("jane.risk."))
                assertTrue(zh.has(key), "Missing Chinese key: " + key);
        assertEquals("跳过未选项目并尝试加入", zh.get("jane.select.try_join").getAsString());
        assertEquals("我已了解风险，仍要尝试加入", zh.get("jane.risk.confirm").getAsString());
    }

    @Test void quickSourceControlsAndUnavailableReasonsExistInBothLanguages() throws Exception {
        JsonObject zh = language("zh_cn");
        JsonObject en = language("en_us");
        for (String key : new String[] {"jane.sync.quick_heading", "jane.sync.quick_trusted",
                "jane.sync.quick_server", "jane.sync.quick_no_trusted", "jane.sync.quick_no_remaining",
                "jane.sync.quick_switching", "jane.sync.quick_stopping",
                "jane.sync.quick_switch_completed",
                "jane.sync.quick_switch_failed"}) {
            assertTrue(zh.has(key), "Missing Chinese key: " + key);
            assertTrue(en.has(key), "Missing English key: " + key);
        }
    }
}
