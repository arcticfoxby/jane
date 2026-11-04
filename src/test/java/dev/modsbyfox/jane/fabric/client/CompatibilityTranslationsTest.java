package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CompatibilityTranslationsTest {
    @Test void introIsShortWhileFullBoundaryAndCloseGameButtonRemain() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("assets/jane/lang/zh_cn.json")) {
            assertNotNull(input);
            var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("这些额外 Mod 可能存在客户端兼容性问题。",
                    json.get("jane.compat.intro3").getAsString());
            assertEquals("禁用所选并关闭游戏", json.get("jane.compat.disable").getAsString());
            for (int i = 1; i <= 4; i++) assertTrue(json.has("jane.compat.boundary" + i));
        }
    }

    @Test void universalDisableRiskAndFailureTextExistsInBothLanguages() throws Exception {
        for (String language : new String[] {"zh_cn", "en_us"}) {
            try (var input = getClass().getClassLoader().getResourceAsStream("assets/jane/lang/" + language + ".json")) {
                assertNotNull(input);
                var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String key : new String[] {"disable_count", "universal_kind", "local_identity_error",
                        "universal_confirm_title", "universal_confirm_title_short", "universal_counts",
                        "universal_explain1", "universal_explain2", "universal_confirm",
                        "universal_confirm_short", "universal_confirm_tiny", "universal_cancel",
                        "universal_scroll_hint", "enlarge_window", "enlarge_window_short", "audit_failed",
                        "universal_confirmation_required",
                        "file_changed", "metadata_changed", "protected_file", "unsafe_file"})
                    assertTrue(json.has("jane.compat." + key), language + ": " + key);
                assertFalse(json.get("jane.compat.universal_explain1").getAsString().isBlank());
                assertFalse(json.get("jane.compat.universal_explain2").getAsString().isBlank());
            }
        }
    }
}
