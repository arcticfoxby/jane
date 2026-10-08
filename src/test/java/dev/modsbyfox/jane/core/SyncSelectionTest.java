package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SyncSelectionTest {
    private static final String HASH = "a".repeat(128);

    private static PendingSyncContext context() {
        List<ManifestEntry> entries = List.of(
                new ManifestEntry("required", "Required", "1", 100, HASH),
                new ManifestEntry("optional", "Optional", "1", 200, HASH),
                new ManifestEntry("present", "Present", "1", 300, HASH));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, entries);
        List<Comparison.Result> compared = Comparison.compare(manifest,
                Map.of("present", new Comparison.LocalMod("present", "1", Path.of("present.jar"))), path -> HASH);
        return new PendingSyncContext("example.org", manifest, compared);
    }

    @Test void conservativeDefaultsAndExactPresentFileStayObjective() {
        PendingSyncContext context = context();
        SyncSelection choice = new SyncSelection(context);
        assertEquals(ClientInstallCategory.SERVER_REQUIRED, choice.category("required"));
        assertEquals(Set.of("required", "optional", "present"), choice.selectedModIds());
        assertEquals(Set.of("required", "optional"), choice.selectedUnmatchedModIds());
        assertTrue(choice.deselectedRequiredModIds().isEmpty());

        choice.setSelected("present", false);
        assertEquals(Comparison.Status.OK, context.results().get(2).status());
        assertEquals(Set.of("required", "optional"), choice.selectedUnmatchedModIds());
        assertTrue(choice.deselectedRequiredModIds().isEmpty());
    }

    @Test void optionalDefaultsOffAndPlayerCanSelectItWhileRequiredCanBeSkipped() {
        SyncSelection choice = new SyncSelection(context(), Map.of("optional", ClientInstallCategory.CLIENT_OPTIONAL));
        assertTrue(choice.isSelected("required"));
        assertFalse(choice.isSelected("optional"));
        assertEquals(Set.of("optional"), choice.defaultOptionalExclusionModIds());
        assertEquals(Set.of("required"), choice.selectedUnmatchedModIds());

        choice.setSelected("required", false);
        choice.setSelected("optional", true);
        assertEquals(Set.of("required"), choice.deselectedRequiredModIds());
        assertEquals(Set.of("optional"), choice.selectedUnmatchedModIds());
        assertTrue(choice.defaultOptionalExclusionModIds().isEmpty());
    }

    @Test void laterSourceAdviceDoesNotOverwriteManualChoice() {
        SyncSelection choice = new SyncSelection(context());
        choice.setSelected("required", false);
        choice.applyCategories(Map.of("required", ClientInstallCategory.CLIENT_OPTIONAL,
                "optional", ClientInstallCategory.CLIENT_OPTIONAL));
        assertFalse(choice.isSelected("required"));
        assertFalse(choice.isSelected("optional"));
        choice.setSelected("optional", true);
        choice.applyCategories(Map.of("optional", ClientInstallCategory.SERVER_REQUIRED));
        assertTrue(choice.isSelected("optional"));
        assertThrows(IllegalArgumentException.class, () -> choice.applyCategories(Map.of("unknown", ClientInstallCategory.CLIENT_OPTIONAL)));
    }

    @Test void retryClassificationAfterTransferStartsCannotChangeFrozenQueue() {
        SyncSelection choice = new SyncSelection(context());
        Set<String> queued = choice.selectedUnmatchedModIds();
        choice.freezeChoices();
        choice.applyCategories(Map.of("optional", ClientInstallCategory.CLIENT_OPTIONAL));
        assertEquals(ClientInstallCategory.CLIENT_OPTIONAL, choice.category("optional"));
        assertEquals(queued, choice.selectedUnmatchedModIds());
        assertThrows(IllegalStateException.class, () -> choice.setSelected("optional", false));
    }

    @Test void digestBindsServerManifestAndSelectionWithoutLeakingFiles() {
        SyncSelection choice = new SyncSelection(context());
        String initial = choice.digest();
        assertTrue(initial.matches("[0-9a-f]{64}"));
        assertTrue(choice.manifestDigest().matches("[0-9a-f]{64}"));
        choice.setSelected("required", false);
        assertNotEquals(initial, choice.digest());
        assertEquals(choice.serverId(), context().serverId());
        assertThrows(UnsupportedOperationException.class, () -> choice.selectedModIds().add("unlisted"));
    }

    @Test void onlyExactEvidenceCanSuggestOptional() {
        assertEquals(ClientInstallCategory.CLIENT_OPTIONAL,
                ClientInstallCategory.fromModrinthEvidence(true, "optional"));
        assertEquals(ClientInstallCategory.SERVER_REQUIRED,
                ClientInstallCategory.fromModrinthEvidence(false, "optional"));
        assertEquals(ClientInstallCategory.SERVER_REQUIRED,
                ClientInstallCategory.fromModrinthEvidence(true, "required"));
        assertEquals(ClientInstallCategory.SERVER_REQUIRED,
                ClientInstallCategory.fromModrinthEvidence(true, null));
    }
}
