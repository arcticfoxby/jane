package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.RequiredManifest;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncSelectionLayoutTest {
    @Test void listAndFixedButtonsDoNotOverlapCommonScaledWindows() {
        int[][] windows = {{854, 480}, {427, 240}, {284, 160}, {214, 120}, {220, 126},
                {1280, 720}, {640, 360}, {426, 240}, {1920, 1080}, {960, 540}, {640, 360}};
        for (int[] window : windows) {
            SyncSelectionLayout layout = SyncSelectionLayout.forSize(window[0], window[1]);
            assertTrue(layout.listRight() > layout.listLeft());
            assertTrue(layout.listTop() < layout.listBottom());
            assertTrue(layout.listBottom() < layout.mainY());
            if (layout.compact()) {
                assertTrue(layout.footerY() + 18 < layout.listTop());
                assertTrue(layout.listTop() > 43);
            } else {
                assertTrue(layout.listTop() > 61);
                assertTrue(layout.mainY() + 20 < layout.footerY());
                assertTrue(layout.footerY() + 20 <= window[1]);
            }
            assertTrue(layout.mainY() + 20 <= window[1]);
            assertTrue(layout.mainX() >= 0);
            assertTrue(layout.mainX() + layout.mainWidth() <= window[0]);
        }
    }

    @Test void declarationAndProgressRemainClippedAboveTheListAtCommonGuiScales() {
        int[][] windows = {{854, 480}, {427, 240}, {284, 160}, {214, 120}, {220, 126},
                {1280, 720}, {640, 360}, {426, 240}, {1920, 1080}};
        for (int[] window : windows) {
            for (boolean downloading : List.of(false, true)) {
                SyncSelectionLayout layout = SyncSelectionLayout.forSize(window[0], window[1], 120, downloading);
                assertTrue(layout.declarationTop() >= (window[1] <= 160 && downloading ? 47 : 40));
                assertTrue(layout.declarationBottom() - layout.declarationTop() - 14 >= 10,
                        "Notice must show at least one scrollable text line at " + window[0] + "x" + window[1]);
                assertTrue(layout.declarationBottom() < layout.listTop());
                assertTrue(layout.listTop() < layout.listBottom());
                assertTrue(layout.listBottom() < layout.mainY());
                assertTrue(layout.mainY() + 20 <= window[1]);
                if (!layout.compact()) assertTrue(layout.mainY() + 20 < layout.footerY());
            }
        }
    }

    @Test void eightyFourMatchedFilesStaySeparateFromTheOnePendingRequiredFile() {
        List<Comparison.Result> compared = sampleComparison();
        Map<String, ClientInstallCategory> categories = compared.stream().collect(java.util.stream.Collectors.toMap(
                result -> result.required().modId(), result -> ClientInstallCategory.SERVER_REQUIRED));
        assertEquals(1, SyncSelectionLayout.pendingRows(compared, categories,
                ClientInstallCategory.SERVER_REQUIRED).size());
        assertEquals("mod84", SyncSelectionLayout.pendingRows(compared, categories,
                ClientInstallCategory.SERVER_REQUIRED).get(0).required().modId());
        assertEquals(84, SyncSelectionLayout.matchedRows(compared).size());
        assertEquals(0, SyncSelectionLayout.pendingRows(compared, categories,
                ClientInstallCategory.CLIENT_OPTIONAL).size());
        assertFalse(SyncSelectionLayout.DEFAULT_MATCHED_OPEN);
        assertFalse(SyncSelectionLayout.DEFAULT_SOURCES_OPEN);
        assertTrue(SyncSelectionLayout.DEFAULT_TRUSTED_SOURCE);
        assertTrue(SyncSelectionLayout.DEFAULT_SERVER_SOURCE);
    }

    @Test void openingAndReopeningUsesOneSourceResolutionWithOneLookup() {
        List<Comparison.Result> compared = sampleComparison();
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL,
                compared.stream().map(Comparison.Result::required).toList());
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, compared));
        assertTrue(SyncSelectionLayout.beginResolutionOnce(session));
        assertEquals(1, session.snapshot().resolutionTotal());
        assertFalse(SyncSelectionLayout.beginResolutionOnce(session));
        assertEquals(1, session.snapshot().resolutionTotal());
    }

    @Test void newLabelsAndFixedNoticeExistInBothLanguages() throws Exception {
        String[] keys = {"jane.select.declaration_title", "jane.select.declaration1",
                "jane.select.declaration2", "jane.sync.download_needed", "jane.sync.resolving_button",
                "jane.sync.matched_group", "jane.sync.pending_count", "jane.sync.source_settings",
                "jane.sync.source_trusted", "jane.sync.source_server", "jane.sync.no_source",
                "jane.sync.download_paused", "jane.sync.selected_completed", "jane.sync.current_item",
                "jane.sync.route_progress", "jane.sync.compact_progress"};
        for (String language : List.of("zh_cn", "en_us")) {
            try (var stream = getClass().getClassLoader().getResourceAsStream(
                    "assets/jane/lang/" + language + ".json")) {
                assertNotNull(stream);
                JsonObject values = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (String key : keys) assertTrue(values.has(key), language + " missing " + key);
            }
        }
    }

    private static List<Comparison.Result> sampleComparison() {
        List<Comparison.Result> results = new ArrayList<>();
        for (int i = 0; i < 85; i++) {
            ManifestEntry entry = new ManifestEntry("mod" + i, "Mod " + i, "1.0", 1024,
                    "a".repeat(128));
            Comparison.LocalMod local = i < 84 ? new Comparison.LocalMod(entry.modId(),
                    entry.version(), Path.of(entry.modId() + ".jar")) : null;
            results.add(new Comparison.Result(entry, local,
                    i < 84 ? Comparison.Status.OK : Comparison.Status.MISSING, i < 84 ? entry.sha512() : null));
        }
        return List.copyOf(results);
    }
}
