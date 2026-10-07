package dev.modsbyfox.jane.core;

import java.util.Optional;
import java.util.UUID;

/** Public marker only; the Provider token and hash whitelist remain the authorization. */
public final class MinecraftTransferMarker {
    public static final UUID PROFILE_ID = UUID.fromString("e8c871c2-6912-475a-9c6e-d9b829ec63f0");
    public static final String PROFILE_NAME = "JaneTransfer";
    public static final int ACK_MAGIC = 0x4a545246;
    public static final int TRANSPORT_VERSION = 1;

    private MinecraftTransferMarker() { }
    public static boolean matches(String name, Optional<UUID> profileId) {
        return PROFILE_NAME.equals(name) && profileId.isPresent() && PROFILE_ID.equals(profileId.get());
    }
}
