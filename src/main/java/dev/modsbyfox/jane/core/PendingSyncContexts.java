package dev.modsbyfox.jane.core;

import java.util.Objects;

public final class PendingSyncContexts {
    private Object connection;
    private PendingSyncContext context;
    private boolean passed;

    public synchronized void begin(Object connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        context = null;
        passed = false;
    }

    public synchronized boolean replace(Object connection, PendingSyncContext context) {
        if (connection == null || this.connection != connection) return false;
        this.context = Objects.requireNonNull(context, "context");
        passed = false;
        return true;
    }

    public synchronized boolean markPassed(Object connection) {
        if (connection == null || this.connection != connection) return false;
        context = null;
        passed = true;
        return true;
    }

    public synchronized boolean takePassed() {
        boolean result = passed;
        clear();
        return result;
    }

    public synchronized PendingSyncContext get() {
        return context;
    }

    public synchronized PendingSyncContext take() {
        PendingSyncContext result = context;
        if (result != null) clear();
        return result;
    }

    public synchronized boolean clearForConnection(Object connection) {
        if (connection == null || this.connection != connection) return false;
        clear();
        return true;
    }

    public synchronized void clearIfContext(PendingSyncContext context) {
        if (context != null && this.context == context) clear();
    }

    public synchronized void clear() {
        connection = null;
        context = null;
        passed = false;
    }
}
