package dev.modsbyfox.jane.core;

/** Installation advice does not change the server's Required Manifest. */
public enum ClientInstallCategory {
    SERVER_REQUIRED,
    CLIENT_OPTIONAL;

    public static ClientInstallCategory fromModrinthEvidence(boolean exactFileVerified, String clientSide) {
        return exactFileVerified && "optional".equals(clientSide) ? CLIENT_OPTIONAL : SERVER_REQUIRED;
    }
}
