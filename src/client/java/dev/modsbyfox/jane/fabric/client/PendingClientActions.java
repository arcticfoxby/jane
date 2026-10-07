package dev.modsbyfox.jane.fabric.client;

import java.util.Objects;

/** A single connection-scoped hand-off from the login worker to the disconnected UI. */
final class PendingClientActions {
    private Object connection;
    private PendingClientAction action;
    private boolean passed;

    synchronized void begin(Object connection) {
        this.connection = Objects.requireNonNull(connection);
        action = null;
        passed = false;
    }

    synchronized boolean replace(Object connection, PendingClientAction action) {
        if (connection == null || this.connection != connection) return false;
        this.action = Objects.requireNonNull(action);
        passed = false;
        return true;
    }

    synchronized boolean markPassed(Object connection) {
        if (connection == null || this.connection != connection) return false;
        action = null;
        passed = true;
        return true;
    }

    synchronized PendingClientAction take() {
        PendingClientAction result = action;
        if (result != null) clear();
        return result;
    }

    synchronized boolean takePassed() {
        boolean result = passed;
        clear();
        return result;
    }

    synchronized void clearForConnection(Object connection) {
        if (connection != null && this.connection == connection) clear();
    }

    synchronized void clearIfContext(dev.modsbyfox.jane.core.PendingSyncContext context) {
        if (action instanceof PendingClientAction.RequiredSync sync && sync.context() == context) clear();
    }

    private void clear() {
        connection = null;
        action = null;
        passed = false;
    }
}
