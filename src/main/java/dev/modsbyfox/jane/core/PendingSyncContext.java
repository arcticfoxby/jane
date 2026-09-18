package dev.modsbyfox.jane.core;

import java.util.List;
import java.util.Objects;

public record PendingSyncContext(String serverAddress, String serverId, RequiredManifest manifest,
                                 List<Comparison.Result> results) {
    public PendingSyncContext(String serverAddress, RequiredManifest manifest, List<Comparison.Result> results) {
        this(ServerIdentity.normalize(serverAddress), ServerIdentity.id(serverAddress), manifest, results);
    }

    public PendingSyncContext {
        serverAddress = ServerIdentity.normalize(serverAddress);
        if (!ServerIdentity.id(serverAddress).equals(serverId)) {
            throw new IllegalArgumentException("Server ID does not match address");
        }
        manifest = Objects.requireNonNull(manifest, "manifest");
        results = List.copyOf(results);
    }
}
