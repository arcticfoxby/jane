package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ManifestEntry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnvironmentGateTest {
    private final ManifestEntry required = new ManifestEntry("required", "Required", "1", 1,
            "a".repeat(128));

    private List<Comparison.Result> results(Comparison.Status status) {
        Comparison.LocalMod local = status == Comparison.Status.MISSING ? null
                : new Comparison.LocalMod("required", "1", Path.of("required.jar"));
        return List.of(new Comparison.Result(required, local, status,
                status == Comparison.Status.OK ? required.sha512() : null));
    }

    private ClientCompatibilityReport report(boolean explicit, boolean other) {
        var explicitMods = explicit ? List.of(new ClientCompatibilityReport.ExtraMod("client", "Client", "1",
                "client.jar", "b".repeat(128), ClientCompatibilityReport.Kind.EXPLICIT_CLIENT,
                Path.of("client.jar"))) : List.<ClientCompatibilityReport.ExtraMod>of();
        var otherMods = other ? List.of(new ClientCompatibilityReport.ExtraMod("other", "Other", "1",
                "other.jar", null, ClientCompatibilityReport.Kind.OTHER_EXTRA,
                Path.of("other.jar"))) : List.<ClientCompatibilityReport.ExtraMod>of();
        return new ClientCompatibilityReport(explicitMods, otherMods,
                ClientCompatibilityReport.fingerprint(explicitMods));
    }

    @Test void onlyCompleteRequiredAndNoExtrasPassDirectly() {
        assertEquals(EnvironmentGate.Route.EXACT_PASS,
                EnvironmentGate.route(results(Comparison.Status.OK), report(false, false)));
        assertEquals(EnvironmentGate.Route.DECISION,
                EnvironmentGate.route(results(Comparison.Status.OK), report(true, false)));
        assertEquals(EnvironmentGate.Route.DECISION,
                EnvironmentGate.route(results(Comparison.Status.OK), report(false, true)));
        assertEquals(EnvironmentGate.Route.DECISION,
                EnvironmentGate.route(results(Comparison.Status.MISSING), report(false, false)));
        assertEquals(EnvironmentGate.Route.DECISION,
                EnvironmentGate.route(results(Comparison.Status.MISSING), report(false, true)));
        assertEquals(EnvironmentGate.Route.DECISION,
                EnvironmentGate.route(results(Comparison.Status.VERSION_MISMATCH), report(false, false)));
        assertEquals(EnvironmentGate.Route.DECISION,
                EnvironmentGate.route(results(Comparison.Status.HASH_MISMATCH), report(false, false)));
        assertEquals(EnvironmentGate.Route.DECISION,
                EnvironmentGate.route(results(Comparison.Status.FILE_ERROR), report(false, false)));
    }
}
