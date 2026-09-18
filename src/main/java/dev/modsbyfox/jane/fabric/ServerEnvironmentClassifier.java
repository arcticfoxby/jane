package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.RequiredEnvironment;
import java.util.Optional;
import net.fabricmc.loader.api.metadata.ModEnvironment;

final class ServerEnvironmentClassifier {
    private ServerEnvironmentClassifier() { }

    static Optional<RequiredEnvironment> fromFabric(ModEnvironment environment) {
        return switch (environment) {
            case SERVER -> Optional.of(RequiredEnvironment.SERVER_ONLY);
            case CLIENT -> Optional.of(RequiredEnvironment.CLIENT_OPTIONAL);
            case UNIVERSAL -> Optional.empty();
        };
    }
}
