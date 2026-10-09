package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OneShotExtraJoinTest {
    private static final String SERVER = ServerIdentity.id("one.example.org");
    private static final RequiredManifest MANIFEST = new RequiredManifest(RequiredManifest.PROTOCOL,
            List.of(new ManifestEntry("required", "Required", "1", 1, "a".repeat(128))));

    private ClientCompatibilityReport report(String version) {
        var extra = new ClientCompatibilityReport.ExtraMod("iris", "Iris", version,
                "iris.jar", null, ClientCompatibilityReport.Kind.OTHER_EXTRA, Path.of("iris.jar"));
        return new ClientCompatibilityReport(List.of(), List.of(extra),
                ClientCompatibilityReport.fingerprint(List.of()));
    }

    @Test void extraOnlyConfirmationIsSingleUseAndBoundToServerManifestAndInventory() {
        OneShotExtraJoin join = new OneShotExtraJoin();
        Object connection = new Object();
        assertFalse(join.consume(connection, SERVER, MANIFEST, report("1")));
        assertTrue(join.arm(SERVER, MANIFEST, report("1")));
        join.beginConnection(connection);
        assertTrue(join.consume(connection, SERVER, MANIFEST, report("1")));
        assertFalse(join.consume(connection, SERVER, MANIFEST, report("1")));

        assertTrue(join.arm(SERVER, MANIFEST, report("1")));
        join.beginConnection(connection);
        assertFalse(join.consume(connection, ServerIdentity.id("two.example.org"), MANIFEST, report("1")));
        assertTrue(join.arm(SERVER, MANIFEST, report("1")));
        join.beginConnection(connection);
        var changed = new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry("required", "Required", "2", 1, "a".repeat(128))));
        assertFalse(join.consume(connection, SERVER, changed, report("1")));
        assertTrue(join.arm(SERVER, MANIFEST, report("1")));
        join.beginConnection(connection);
        assertFalse(join.consume(connection, SERVER, MANIFEST, report("2")));
    }

    @Test void aSecondConnectionInvalidatesUnconsumedConfirmation() {
        OneShotExtraJoin join = new OneShotExtraJoin();
        Object expected = new Object();
        assertTrue(join.arm(SERVER, MANIFEST, report("1")));
        join.beginConnection(expected);
        join.beginConnection(new Object());
        assertFalse(join.consume(expected, SERVER, MANIFEST, report("1")));
    }
}
