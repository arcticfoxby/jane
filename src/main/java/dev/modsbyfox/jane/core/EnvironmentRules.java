package dev.modsbyfox.jane.core;

public final class EnvironmentRules {
    private EnvironmentRules() { }

    public static ClientSyncDecision fromModrinth(String value) {
        if (value == null) return ClientSyncDecision.SYNC_CONSERVATIVE;
        return switch (value) {
            case "client_and_server" -> ClientSyncDecision.SYNC;
            case "server_only", "dedicated_server_only", "server_only_client_optional", "client_only",
                    "client_only_server_optional", "client_or_server", "client_or_server_prefers_both",
                    "singleplayer_only" -> ClientSyncDecision.EXCLUDE;
            default -> ClientSyncDecision.SYNC_CONSERVATIVE;
        };
    }

    public static boolean unknownFutureValue(String value) {
        return value != null && !"unknown".equals(value)
                && fromModrinth(value) == ClientSyncDecision.SYNC_CONSERVATIVE;
    }
}
