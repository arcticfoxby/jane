package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.SyncSelection;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncSelectionScreenTest {
    @Test void secondaryListAndFixedNoticeFitCommonGuiScales() {
        int[][] windows = {{1280, 720}, {640, 360}, {427, 240}, {284, 160},
                {214, 120}, {1920, 1080}, {960, 540}, {426, 240}};
        for (int[] window : windows) {
            SelectionScreenLayout layout = SelectionScreenLayout.forSize(window[0], window[1]);
            assertTrue(layout.left() >= 0);
            assertTrue(layout.right() + 8 <= window[0]);
            assertTrue(layout.declarationTop() >= (layout.compact() ? 19 : 34));
            assertTrue(layout.declarationBottom() - layout.declarationTop() >= 24);
            assertTrue(layout.listTop() > layout.declarationBottom());
            assertTrue(layout.listHeight() >= 40, window[0] + "x" + window[1]);
            assertTrue(layout.listBottom() + 5 <= window[1] - 24);
            assertTrue(window[1] - 24 + 20 <= window[1]);
        }
    }

    @Test void eightyFourMatchedRowsStaySeparateFromSinglePendingRequired() {
        List<Comparison.Result> results = new ArrayList<>();
        for (int i = 0; i < 85; i++) {
            ManifestEntry entry = entry("mod" + i);
            Comparison.LocalMod local = i == 84 ? null
                    : new Comparison.LocalMod(entry.modId(), entry.version(), Path.of(entry.modId() + ".jar"));
            results.add(new Comparison.Result(entry, local,
                    i == 84 ? Comparison.Status.MISSING : Comparison.Status.OK,
                    i == 84 ? null : entry.sha512()));
        }
        Map<String, ClientInstallCategory> categories = results.stream().collect(java.util.stream.Collectors.toMap(
                result -> result.required().modId(), result -> ClientInstallCategory.SERVER_REQUIRED));
        List<Comparison.Result> pending = SelectionScreenRows.pending(results, categories,
                ClientInstallCategory.SERVER_REQUIRED);
        assertEquals(List.of("mod84"), pending.stream().map(result -> result.required().modId()).toList());
        assertEquals(84, SelectionScreenRows.matched(results).size());
    }

    @Test void selectionDefaultsArePreservedWhenMovedOffMainScreen() {
        ManifestEntry required = entry("required");
        ManifestEntry optional = entry("optional");
        List<Comparison.Result> results = List.of(
                new Comparison.Result(required, null, Comparison.Status.MISSING, null),
                new Comparison.Result(optional, null, Comparison.Status.MISSING, null));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(required, optional));
        SyncSelection selection = new SyncSelection(new PendingSyncContext("example.org", manifest, results));
        selection.applyCategories(Map.of("optional", ClientInstallCategory.CLIENT_OPTIONAL));
        assertTrue(selection.isSelected("required"));
        assertTrue(selection.isSelected("optional"));
        assertEquals(List.of("required"), SelectionScreenRows.pending(results, selection.categories(),
                ClientInstallCategory.SERVER_REQUIRED).stream()
                .map(result -> result.required().modId()).toList());
        assertEquals(List.of("optional"), SelectionScreenRows.pending(results, selection.categories(),
                ClientInstallCategory.CLIENT_OPTIONAL).stream()
                .map(result -> result.required().modId()).toList());
    }

    @Test void fileErrorIsNeverListedAsAdjustableOptionalCandidate() {
        ManifestEntry broken = entry("broken");
        Comparison.Result result = new Comparison.Result(broken, null, Comparison.Status.FILE_ERROR, null);
        assertFalse(SelectionScreenRows.adjustable(result, ClientInstallCategory.CLIENT_OPTIONAL));
        assertTrue(SelectionScreenRows.pending(List.of(result),
                Map.of("broken", ClientInstallCategory.CLIENT_OPTIONAL),
                ClientInstallCategory.CLIENT_OPTIONAL).isEmpty());
    }

    @Test void onlyUnmatchedCandidateRowsCanBeToggled() {
        ManifestEntry entry = entry("example");
        Comparison.Result missing = new Comparison.Result(entry, null, Comparison.Status.MISSING, null);
        Comparison.Result matched = new Comparison.Result(entry,
                new Comparison.LocalMod("example", entry.version(), Path.of("example.jar")),
                Comparison.Status.OK, entry.sha512());
        assertFalse(SelectionScreenRows.adjustable(missing, ClientInstallCategory.SERVER_REQUIRED));
        assertTrue(SelectionScreenRows.adjustable(missing, ClientInstallCategory.CLIENT_OPTIONAL));
        assertFalse(SelectionScreenRows.adjustable(matched, ClientInstallCategory.CLIENT_OPTIONAL));
    }

    @Test void secondaryScreenAndMainEntryLabelsExistInBothLanguages() throws Exception {
        for (String language : List.of("zh_cn", "en_us")) {
            try (var stream = getClass().getClassLoader().getResourceAsStream(
                    "assets/jane/lang/" + language + ".json")) {
                assertNotNull(stream);
                JsonObject translations = JsonParser.parseReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String key : List.of("jane.select.screen_title", "jane.select.screen_hint",
                        "jane.sync.select_items", "jane.sync.select_short",
                        "jane.sync.confirm_move_hint_compact", "jane.select.declaration3"))
                    assertTrue(translations.has(key), language + " missing " + key);
            }
        }
    }

    private static ManifestEntry entry(String modId) {
        return new ManifestEntry(modId, modId, "1.0", 1024, "a".repeat(128));
    }
}
