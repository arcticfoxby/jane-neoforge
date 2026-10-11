package dev.modsbyfox.jane.core;

/** Wire result for the Jane configuration gate; an override never claims an exact file match. */
public enum LoginStatus {
    EXACT_PASS(0), ACTION_REQUIRED(1), PROTOCOL_ERROR(2), USER_OVERRIDE(3);

    private final int code;

    LoginStatus(int code) { this.code = code; }

    public int code() { return code; }

    public boolean allowsJaneGate() { return this == EXACT_PASS || this == USER_OVERRIDE; }

    public static LoginStatus fromCode(int code) {
        for (LoginStatus value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("Unknown Jane login status");
    }
}
