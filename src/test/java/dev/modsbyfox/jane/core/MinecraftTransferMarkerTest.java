package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MinecraftTransferMarkerTest {
    @Test void exactNameAndUuidAreRequired() {
        assertTrue(MinecraftTransferMarker.matches(MinecraftTransferMarker.PROFILE_NAME,
                Optional.of(MinecraftTransferMarker.PROFILE_ID)));
        assertFalse(MinecraftTransferMarker.matches("NormalUser", Optional.of(MinecraftTransferMarker.PROFILE_ID)));
        assertFalse(MinecraftTransferMarker.matches(MinecraftTransferMarker.PROFILE_NAME, Optional.empty()));
        assertFalse(MinecraftTransferMarker.matches(MinecraftTransferMarker.PROFILE_NAME, Optional.of(UUID.randomUUID())));
        for (int index = 0; index < 1_024; index++) {
            assertFalse(MinecraftTransferMarker.matches(MinecraftTransferMarker.PROFILE_NAME, Optional.of(UUID.randomUUID())));
        }
    }
}
