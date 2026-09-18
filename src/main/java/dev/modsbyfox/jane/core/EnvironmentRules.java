package dev.modsbyfox.jane.core;

import java.io.IOException;

public final class EnvironmentRules {
    private EnvironmentRules() { }

    public static RequiredEnvironment fromModrinth(String value) {
        if (value == null) return RequiredEnvironment.UNKNOWN;
        return switch (value) {
            case "client_and_server" -> RequiredEnvironment.CLIENT_REQUIRED;
            case "server_only", "dedicated_server_only" -> RequiredEnvironment.SERVER_ONLY;
            case "server_only_client_optional", "client_only", "client_only_server_optional",
                    "client_or_server", "client_or_server_prefers_both" -> RequiredEnvironment.CLIENT_OPTIONAL;
            default -> RequiredEnvironment.UNKNOWN;
        };
    }

    public static RequiredEnvironment applyOverride(String modId, RequiredEnvironment automatic,
                                                    boolean hasOverride) throws IOException {
        if (hasOverride) {
            if (automatic != RequiredEnvironment.UNKNOWN) {
                throw new IOException("Environment override for " + modId + " conflicts with " + automatic);
            }
            return RequiredEnvironment.CLIENT_REQUIRED;
        }
        if (automatic == RequiredEnvironment.UNKNOWN) {
            throw new IOException("Cannot determine client requirement for mod " + modId
                    + "; add an explicit CLIENT_REQUIRED override if this exact mod is required on remote clients,"
                    + " or remove it from requiredMods");
        }
        return automatic;
    }
}
