package dev.modsbyfox.jane.core;

/** An ephemeral capability for files in one login's required manifest. */
public record ServerProviderOffer(int port, String token) {
    public ServerProviderOffer {
        if (port < 1 || port > 65535 || token == null || !token.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid ServerProvider offer");
        }
    }

    @Override public String toString() { return "ServerProviderOffer[port=" + port + ", token=<redacted>]"; }
}
