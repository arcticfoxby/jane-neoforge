package dev.modsbyfox.jane.core;

/** Whether a server candidate is required on a remote client. */
public enum RequiredEnvironment {
    CLIENT_REQUIRED,
    SERVER_ONLY,
    CLIENT_OPTIONAL,
    UNKNOWN
}
