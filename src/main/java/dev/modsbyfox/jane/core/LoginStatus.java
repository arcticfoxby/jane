package dev.modsbyfox.jane.core;

/** The one-byte Jane login result. USER_OVERRIDE is a declared risk, not an exact match. */
public enum LoginStatus {
    EXACT_PASS(0), ACTION_REQUIRED(1), PROTOCOL_ERROR(2), USER_OVERRIDE(3);

    private final int code;

    LoginStatus(int code) { this.code = code; }

    public int code() { return code; }

    public boolean allowsJaneGate() { return this == EXACT_PASS || this == USER_OVERRIDE; }

    public static LoginStatus fromCode(int code) {
        for (LoginStatus status : values()) if (status.code == code) return status;
        throw new IllegalArgumentException("Unknown Jane login status");
    }
}
