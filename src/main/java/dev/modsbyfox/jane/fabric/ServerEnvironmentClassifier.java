package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.ClientSyncDecision;
import java.util.Optional;
import net.fabricmc.loader.api.metadata.ModEnvironment;

final class ServerEnvironmentClassifier {
    private ServerEnvironmentClassifier() { }

    static Optional<ClientSyncDecision> fromFabric(ModEnvironment environment) {
        return switch (environment) {
            case SERVER, CLIENT -> Optional.of(ClientSyncDecision.EXCLUDE);
            case UNIVERSAL -> Optional.empty();
        };
    }
}
