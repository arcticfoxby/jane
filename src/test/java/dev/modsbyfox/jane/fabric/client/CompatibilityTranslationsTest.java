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
}
