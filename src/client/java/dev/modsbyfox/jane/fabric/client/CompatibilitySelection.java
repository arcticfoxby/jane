package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.PathSafety;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Per-screen, never persisted selection of extra physical JARs. */
final class CompatibilitySelection {
    private final ClientCompatibilityReport report;
    private final Set<ClientCompatibilityReport.ExtraMod> selected = new LinkedHashSet<>();

    CompatibilitySelection(ClientCompatibilityReport report) {
        this.report = Objects.requireNonNull(report, "report");
    }

    boolean selectable(ClientCompatibilityReport.ExtraMod mod) {
        return mod != null && safeFilename(mod.filename())
                && (mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT
                ? mod.sha512() != null && report.explicitClientMods().contains(mod)
                : mod.fileIdentity() != null && report.otherExtraMods().contains(mod));
    }

    private static boolean safeFilename(String filename) {
        try {
            PathSafety.safeExistingJarName(filename);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    boolean toggle(ClientCompatibilityReport.ExtraMod mod) {
        if (!selectable(mod)) return false;
        if (!selected.add(mod)) selected.remove(mod);
        return true;
    }

    boolean contains(ClientCompatibilityReport.ExtraMod mod) { return selected.contains(mod); }
    List<ClientCompatibilityReport.ExtraMod> selected() { return List.copyOf(selected); }
    int size() { return selected.size(); }

    int explicitCount() {
        return (int) selected.stream().filter(mod -> mod.kind() == ClientCompatibilityReport.Kind.EXPLICIT_CLIENT).count();
    }

    int otherCount() { return size() - explicitCount(); }
    boolean needsUniversalConfirmation() { return otherCount() > 0; }
}
