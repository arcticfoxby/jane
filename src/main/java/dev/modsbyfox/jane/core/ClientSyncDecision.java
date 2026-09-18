package dev.modsbyfox.jane.core;

/** Server decision; download-source availability is resolved separately by the client. */
public enum ClientSyncDecision {
    SYNC,
    EXCLUDE,
    SYNC_CONSERVATIVE;

    public boolean entersManifest() { return this != EXCLUDE; }
}
