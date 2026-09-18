package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PendingSyncContextsTest {
    private static PendingSyncContext context(String address) {
        ManifestEntry entry = new ManifestEntry("examplemod", "Example Mod", "1.0", 10, "0".repeat(128));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(entry));
        return new PendingSyncContext(address, manifest,
                List.of(new Comparison.Result(entry, null, Comparison.Status.MISSING, null)));
    }

    @Test
    void capturesAndKeepsIdentityAfterDisconnectAndScreenHandoff() {
        PendingSyncContexts state = new PendingSyncContexts();
        Object connection = new Object();
        PendingSyncContext first = context("PLAY.Example.com:25565");
        state.begin(connection);
        assertNull(state.take()); // A disconnect tick can precede asynchronous comparison.
        assertTrue(state.replace(connection, first));
        assertSame(first, state.get());
        PendingSyncContext handedToScreen = state.take();
        assertSame(first, handedToScreen);
        assertNull(state.get());
        assertEquals("play.example.com:25565", handedToScreen.serverAddress());
        assertEquals(ServerIdentity.id("play.example.com"), handedToScreen.serverId());
        assertEquals(1, handedToScreen.results().size());
    }

    @Test
    void newConnectionReplacesOldContextAndRejectsLateOldResult() {
        PendingSyncContexts state = new PendingSyncContexts();
        Object firstConnection = new Object();
        Object secondConnection = new Object();
        PendingSyncContext first = context("a.example.com");
        PendingSyncContext second = context("b.example.com");
        state.begin(firstConnection);
        assertTrue(state.replace(firstConnection, first));
        state.begin(secondConnection);
        assertNull(state.get());
        assertFalse(state.replace(firstConnection, first));
        assertTrue(state.replace(secondConnection, second));
        state.clearIfContext(first); // A cancelled screen must not clear B.
        assertSame(second, state.get());
    }

    @Test
    void cancelAndPassClearMismatchContext() {
        PendingSyncContexts state = new PendingSyncContexts();
        Object connection = new Object();
        PendingSyncContext first = context("a.example.com");
        state.begin(connection);
        assertTrue(state.replace(connection, first));
        state.clearIfContext(first);
        assertNull(state.get());
        state.begin(connection);
        assertTrue(state.replace(connection, first));
        assertTrue(state.markPassed(connection));
        assertNull(state.get());
        assertTrue(state.takePassed());
        assertFalse(state.takePassed());
    }

    @Test
    void nonJaneConnectionAndSingleplayerJoinCannotReuseOldContext() {
        PendingSyncContexts state = new PendingSyncContexts();
        Object janeConnection = new Object();
        state.begin(janeConnection);
        assertTrue(state.replace(janeConnection, context("a.example.com")));
        state.begin(new Object()); // Ordinary server or local login has no Jane manifest.
        assertNull(state.get());
        assertFalse(state.takePassed()); // JOIN also clears any remaining login state.
        assertNull(state.get());
    }
}
