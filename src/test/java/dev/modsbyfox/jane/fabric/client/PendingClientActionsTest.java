package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ServerIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

class PendingClientActionsTest {
    private PendingSyncContext context(String address) {
        ManifestEntry entry = new ManifestEntry("examplemod", "Example", "1", 10, "a".repeat(128));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(entry));
        return new PendingSyncContext(address, manifest,
                List.of(new Comparison.Result(entry, null, Comparison.Status.MISSING, null)));
    }

    private PendingClientAction.RequiredSync action(PendingSyncContext context) {
        // This state-machine test does not start Minecraft, so no ServerData is available.
        return new PendingClientAction.RequiredSync(context, null, false);
    }

    @Test void mismatchContextSurvivesDisconnectAndRejectsStaleConnection() {
        PendingClientActions actions = new PendingClientActions();
        Object firstConnection = new Object();
        Object secondConnection = new Object();
        PendingSyncContext first = context("PLAY.Example.com:25565");
        PendingSyncContext second = context("b.example.com");
        actions.begin(firstConnection);
        assertNull(actions.take());
        assertTrue(actions.replace(firstConnection, action(first)));
        actions.begin(secondConnection);
        assertFalse(actions.replace(firstConnection, action(first)));
        assertTrue(actions.replace(secondConnection, action(second)));
        actions.clearIfContext(first);
        PendingClientAction taken = actions.take();
        assertInstanceOf(PendingClientAction.RequiredSync.class, taken);
        assertSame(second, ((PendingClientAction.RequiredSync) taken).context());
        assertEquals(ServerIdentity.id("b.example.com"), second.serverId());
        assertEquals("play.example.com:25565", first.serverAddress());
    }

    @Test void passAndNewConnectionClearPriorAction() {
        PendingClientActions actions = new PendingClientActions();
        Object connection = new Object();
        PendingSyncContext sync = context("a.example.com");
        actions.begin(connection);
        assertTrue(actions.replace(connection, action(sync)));
        actions.clearIfContext(sync);
        assertNull(actions.take());
        actions.begin(connection);
        assertTrue(actions.replace(connection, action(sync)));
        assertTrue(actions.markPassed(connection));
        assertNull(actions.take());
        assertTrue(actions.takePassed());
        assertFalse(actions.takePassed());
        actions.begin(connection);
        assertTrue(actions.replace(connection, action(sync)));
        actions.begin(new Object());
        assertNull(actions.take());
    }

    @Test void decisionCarriesComparisonAcrossDisconnectButNotIntoAnotherConnection() {
        PendingClientActions actions = new PendingClientActions();
        PendingSyncContext first = context("first.example.com");
        var report = new ClientCompatibilityReport(List.of(), List.of(),
                ClientCompatibilityReport.fingerprint(List.of()));
        Object login = new Object();
        actions.begin(login);
        assertTrue(actions.replace(login,
                new PendingClientAction.EnvironmentDecision(first, report, null, false)));
        var pending = assertInstanceOf(PendingClientAction.EnvironmentDecision.class, actions.take());
        assertSame(first, pending.context());
        assertSame(report, pending.report());
        assertNull(actions.take());

        actions.begin(login);
        assertTrue(actions.replace(login,
                new PendingClientAction.EnvironmentDecision(first, report, null, false)));
        actions.begin(new Object());
        assertNull(actions.take());
    }
}
