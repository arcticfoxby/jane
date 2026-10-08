package dev.modsbyfox.jane.core;

/** Client installation advice. The physical manifest and comparison remain authoritative. */
public enum ClientInstallCategory {
    SERVER_REQUIRED,
    CLIENT_OPTIONAL;

    public static ClientInstallCategory fromModrinthEvidence(boolean exactFileVerified, String clientSide) {
        return exactFileVerified && "optional".equals(clientSide) ? CLIENT_OPTIONAL : SERVER_REQUIRED;
    }
}
