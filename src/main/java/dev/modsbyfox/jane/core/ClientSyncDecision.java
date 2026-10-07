package dev.modsbyfox.jane.core;

/** Server decision; download-source availability is resolved separately by the client. */
public enum ClientSyncDecision {
    SYNC,
    EXCLUDE;

    public boolean entersManifest() { return this != EXCLUDE; }
}
