package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompatibilitySelectionTest {
    private static ClientCompatibilityReport.ExtraMod mod(String id, ClientCompatibilityReport.Kind kind,
                                                           String hash) {
        return new ClientCompatibilityReport.ExtraMod(id, id, "1", id + ".jar", hash, kind,
                Path.of("mods", id + ".jar"), kind == ClientCompatibilityReport.Kind.OTHER_EXTRA
                ? new ClientCompatibilityReport.FileIdentity(1, FileTime.fromMillis(0), null) : null);
    }

    @Test void bothKindsStartUncheckedAndAreIndependent() {
        var explicit = mod("sodium", ClientCompatibilityReport.Kind.EXPLICIT_CLIENT, "a".repeat(128));
        var other = mod("twilightforest", ClientCompatibilityReport.Kind.OTHER_EXTRA, null);
        var report = new ClientCompatibilityReport(List.of(explicit), List.of(other),
                ClientCompatibilityReport.fingerprint(List.of(explicit)));
        var selection = new CompatibilitySelection(report);
        assertEquals(0, selection.size());
        assertFalse(selection.needsUniversalConfirmation());
        assertTrue(selection.toggle(other));
        assertEquals(1, selection.otherCount());
        assertTrue(selection.needsUniversalConfirmation());
        assertTrue(selection.toggle(explicit));
        assertEquals(1, selection.explicitCount());
        assertEquals(List.of(other, explicit), selection.selected());
        assertTrue(selection.toggle(other));
        assertEquals(List.of(explicit), selection.selected());
        assertFalse(selection.needsUniversalConfirmation());
        assertEquals(0, new CompatibilitySelection(report).size(), "a later screen must not restore selection");
    }

    @Test void unverifiedExplicitAndForeignEntryCannotBeSelected() {
        var unverified = mod("iris", ClientCompatibilityReport.Kind.EXPLICIT_CLIENT, null);
        var other = mod("twilightforest", ClientCompatibilityReport.Kind.OTHER_EXTRA, null);
        var report = new ClientCompatibilityReport(List.of(unverified), List.of(other),
                ClientCompatibilityReport.fingerprint(List.of(unverified)));
        var selection = new CompatibilitySelection(report);
        assertFalse(selection.toggle(unverified));
        assertFalse(selection.toggle(mod("foreign", ClientCompatibilityReport.Kind.OTHER_EXTRA, null)));
        assertTrue(selection.toggle(other), "OTHER_EXTRA hash is intentionally deferred until preparation");
        assertEquals(1, selection.size());
    }

    @Test void otherWithUnknownFileIdentityRemainsVisibleButCannotBeSelected() {
        var unknown = new ClientCompatibilityReport.ExtraMod("unknown", "Unknown", "1", "unknown.jar",
                null, ClientCompatibilityReport.Kind.OTHER_EXTRA, Path.of("mods", "unknown.jar"));
        var report = new ClientCompatibilityReport(List.of(), List.of(unknown),
                ClientCompatibilityReport.fingerprint(List.of()));
        var selection = new CompatibilitySelection(report);
        assertFalse(selection.selectable(unknown));
        assertFalse(selection.toggle(unknown));
        assertEquals(0, selection.size());
    }

    @Test void batchUnsafeFilenameCannotBeSelectedEvenWithFileIdentity() {
        var unsafe = new ClientCompatibilityReport.ExtraMod("unsafe", "Unsafe", "1", "evil%.jar",
                null, ClientCompatibilityReport.Kind.OTHER_EXTRA, Path.of("mods", "evil%.jar"),
                new ClientCompatibilityReport.FileIdentity(1, FileTime.fromMillis(0), null));
        var report = new ClientCompatibilityReport(List.of(), List.of(unsafe),
                ClientCompatibilityReport.fingerprint(List.of()));
        assertFalse(new CompatibilitySelection(report).selectable(unsafe));
    }
}
