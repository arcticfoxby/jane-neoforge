package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;

public final class StagedFileVerifier {
    private StagedFileVerifier() { }

    public static void verify(Path file, ManifestEntry target) throws IOException {
        if (Files.size(file) != target.fileSize() || !target.sha512().equals(Hashing.sha512(file))) {
            throw new IOException("Downloaded JAR size or SHA-512 mismatch");
        }
    }

    public static void verifyAndStage(Path part, Path staged, ManifestEntry target,
                                      JaneSyncSession session, BooleanSupplier cancelled) throws IOException {
        session.update(target.modId(), JaneSyncSession.RuntimeState.VERIFYING, target.fileSize());
        try {
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            verify(part, target);
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            Files.move(part, staged, StandardCopyOption.ATOMIC_MOVE);
            session.update(target.modId(), JaneSyncSession.RuntimeState.READY, target.fileSize());
        } catch (IOException | RuntimeException exception) {
            session.update(target.modId(), JaneSyncSession.RuntimeState.FAILED, target.fileSize());
            try { Files.deleteIfExists(part); }
            catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
    }
}
