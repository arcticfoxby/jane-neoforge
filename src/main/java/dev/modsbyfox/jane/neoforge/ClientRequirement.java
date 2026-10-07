package dev.modsbyfox.jane.neoforge;

/** Whether the server's physical mod JAR belongs in the client manifest. */
public enum ClientRequirement {
    REQUIRED,
    NOT_REQUIRED,
    /** Insufficient trusted evidence. Callers must include this mod in the manifest. */
    UNKNOWN
}
