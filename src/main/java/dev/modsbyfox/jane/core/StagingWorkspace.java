package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One sync identity and one staging directory across all providers. */
public final class StagingWorkspace {
    private final String syncId;
    private final Path directory;
    private final List<UpdatePlan.Operation> operations = new ArrayList<>();
    private final Set<String> names = new HashSet<>();

    private StagingWorkspace(String syncId, Path directory) {
        this.syncId = syncId;
        this.directory = directory;
    }

    public static StagingWorkspace create(Path gameDir) throws IOException {
        String id = UUID.randomUUID().toString();
        return new StagingWorkspace(id, PathSafety.janeDirectory(gameDir, "staging", id));
    }

    public String syncId() { return syncId; }
    public Path directory() { return directory; }
    public synchronized void add(UpdatePlan.Operation operation) {
        if (!names.add(operation.newFile().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Duplicate staged file");
        }
        operations.add(operation);
    }
    public synchronized List<UpdatePlan.Operation> operations() { return List.copyOf(operations); }

    /** Creates pending only after every required item is already present or verified READY. */
    public synchronized UpdatePlan prepareIfComplete(Path gameDir, JaneSyncSession session) throws IOException {
        return prepareIfComplete(gameDir, session, () -> false);
    }

    public synchronized UpdatePlan prepareIfComplete(Path gameDir, JaneSyncSession session,
                                                     java.util.function.BooleanSupplier cancelled) throws IOException {
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        if (snapshot.resolution() == null
                || snapshot.resolution().count(ResolutionPlan.Availability.LOOKUP_FAILED) != 0
                || snapshot.resolution().count(ResolutionPlan.Availability.UNRESOLVED) != 0
                || snapshot.resolution().count(ResolutionPlan.Availability.LOCAL_ERROR) != 0
                || snapshot.localErrorCount() != 0
                || snapshot.items().stream().anyMatch(item ->
                (item.item().availability() == ResolutionPlan.Availability.TRUSTED_AVAILABLE
                        || item.item().availability() == ResolutionPlan.Availability.SERVER_ONLY)
                        && item.state() != JaneSyncSession.RuntimeState.READY)) return null;
        List<ResolutionPlan.Item> downloads = snapshot.resolution().items().stream()
                .filter(item -> item.availability() == ResolutionPlan.Availability.TRUSTED_AVAILABLE
                        || item.availability() == ResolutionPlan.Availability.SERVER_ONLY).toList();
        if (operations.size() != downloads.size()) throw new IOException("Incomplete staging operation list");
        if (!PathSafety.janeDirectory(gameDir, "staging", syncId).equals(directory))
            throw new IOException("Staging workspace changed");
        for (ResolutionPlan.Item item : downloads) {
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            UpdatePlan.Operation op = operations.stream().filter(candidate -> candidate.modId().equals(
                    item.comparison().required().modId())).findFirst().orElseThrow(() -> new IOException("Missing staged operation"));
            ManifestEntry entry = item.comparison().required();
            if (!op.newHash().equals(entry.sha512()) || op.size() != entry.fileSize())
                throw new IOException("Staging operation differs from manifest");
            Path staged = directory.resolve(op.newFile());
            if (Files.isSymbolicLink(staged) || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Staged file is not a regular JAR");
            StagedFileVerifier.verify(staged, entry);
        }
        if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
        UpdatePlan plan = new UpdatePlan(syncId, session.context().serverId(),
                BackupTimestamp.next(gameDir, session.context().serverId()), operations);
        PendingStore.create(gameDir, plan);
        return plan;
    }

    /** Only removes direct children of this captured workspace, never another sync ID. */
    public void discard(Path gameDir) throws IOException {
        Path expectedParent = gameDir.toRealPath().resolve("jane").resolve("staging");
        if (!syncId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || Files.isSymbolicLink(expectedParent.getParent()) || Files.isSymbolicLink(expectedParent)
                || Files.isSymbolicLink(directory)
                || !directory.getParent().equals(expectedParent) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe staging workspace cleanup");
        }
        if (!expectedParent.toRealPath().equals(expectedParent)
                || !directory.toRealPath().getParent().equals(expectedParent.toRealPath())) {
            throw new IOException("Staging workspace escaped current instance");
        }
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                if (!file.getParent().equals(directory) || Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected staging workspace child");
                }
                Files.delete(file);
            }
        }
        Files.delete(directory);
    }
}
