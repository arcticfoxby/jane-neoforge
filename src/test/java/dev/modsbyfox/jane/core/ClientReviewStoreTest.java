package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

class ClientReviewStoreTest {
    @TempDir Path game;
    private static final String SERVER = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(128);

    @Test void noRecordThenSameThenChangedFingerprint() throws Exception {
        assertTrue(ClientReviewStore.reviewRequired(game, SERVER, FINGERPRINT));
        ClientReviewStore.acknowledge(game, SERVER, FINGERPRINT);
        assertFalse(ClientReviewStore.reviewRequired(game, SERVER, FINGERPRINT));
        assertTrue(ClientReviewStore.reviewRequired(game, SERVER, "c".repeat(128)));
        String json = Files.readString(game.resolve("jane/client-review/" + SERVER + ".json"));
        assertFalse(json.contains("sodium"));
        assertFalse(json.contains(".jar"));
    }

    @Test void corruptOrUnknownRecordFailsSafe() throws Exception {
        Path file = PathSafety.janeDirectory(game, "client-review").resolve(SERVER + ".json");
        Files.writeString(file, "not json");
        assertTrue(ClientReviewStore.reviewRequired(game, SERVER, FINGERPRINT));
        Files.writeString(file, "{\"schemaVersion\":2,\"serverId\":\"" + SERVER
                + "\",\"fingerprint\":\"" + FINGERPRINT + "\",\"action\":\"DIRECT_JOIN\"}");
        assertTrue(ClientReviewStore.reviewRequired(game, SERVER, FINGERPRINT));
    }

    @Test void symlinkRecordFailsSafe() throws Exception {
        Path file = PathSafety.janeDirectory(game, "client-review").resolve(SERVER + ".json");
        Path target = Files.writeString(game.resolve("other.txt"), "{}");
        try { Files.createSymbolicLink(file, target); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            Assumptions.abort("Symlinks unavailable on this host");
        }
        assertTrue(ClientReviewStore.reviewRequired(game, SERVER, FINGERPRINT));
        assertThrows(java.io.IOException.class, () -> ClientReviewStore.acknowledge(game, SERVER, FINGERPRINT));
    }
}
