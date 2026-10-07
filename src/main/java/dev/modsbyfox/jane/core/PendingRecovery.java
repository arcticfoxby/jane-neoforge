package dev.modsbyfox.jane.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.BackupRetention;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class PendingRecovery {
    public record Item(String syncId, UpdatePlan plan, boolean failed, String issue) { }

    private PendingRecovery() { }

    public static List<Item> scan(Path gameDir) throws IOException {
        Path jane = gameDir.resolve("jane");
        if (!Files.exists(jane, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Path janeRoot = PathSafety.janeDirectory(gameDir);
        if (!Files.exists(janeRoot.resolve("pending"), LinkOption.NOFOLLOW_LINKS)) return List.of();
        Path root = PathSafety.janeDirectory(gameDir, "pending");
        List<Item> items = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.limit(128).toList()) {
                String id = dir.getFileName().toString();
                if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    UpdatePlan plan = read(dir.resolve("pending.json"));
                    if (!plan.syncId().equals(id)) throw new IOException("Pending ID mismatch");
                    if (Files.exists(dir.resolve("success.marker"), LinkOption.NOFOLLOW_LINKS)) {
                        finish(gameDir, plan);
                    } else {
                        items.add(new Item(id, plan, Files.exists(dir.resolve("failed.marker"), LinkOption.NOFOLLOW_LINKS), null));
                    }
                } catch (IOException | RuntimeException exception) {
                    items.add(new Item(id, null, Files.exists(dir.resolve("failed.marker"), LinkOption.NOFOLLOW_LINKS), exception.getMessage()));
                }
            }
        }
        return List.copyOf(items);
    }

    public static UpdatePlan prepareRetry(Path gameDir, Item item) throws IOException {
        if (item.plan() == null) throw new IOException("Pending metadata cannot be read");
        UpdatePlan old = item.plan();
        verifyForLaunch(gameDir, old);
        String timestamp = BackupTimestamp.next(gameDir, old.serverId());
        UpdatePlan retry = new UpdatePlan(old.syncId(), old.serverId(), timestamp, old.operations());
        PendingStore.create(gameDir, retry);
        Path pending = PathSafety.janeDirectory(gameDir, "pending", old.syncId());
        Files.deleteIfExists(pending.resolve("failed.marker"));
        return retry;
    }

    public static void verifyForLaunch(Path gameDir, UpdatePlan plan) throws IOException {
        Path staging = PathSafety.janeDirectory(gameDir, "staging", plan.syncId());
        for (UpdatePlan.Operation op : plan.operations()) {
            Path source = staging.resolve(op.newFile());
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) != op.size()
                    || !Hashing.sha512(source).equals(op.newHash())) throw new IOException("Staged JAR is no longer verified");
            if (op.kind() == UpdatePlan.Kind.REPLACE) {
                Path previous = PathSafety.existingJarNamedInMods(gameDir, op.oldFile());
                if (!Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS) || !Hashing.sha512(previous).equals(op.oldHash())) {
                    throw new IOException("Old JAR was not restored; retry is unsafe");
                }
            }
            Path target = PathSafety.newJarInMods(gameDir, op.newFile());
            if (Files.exists(target) && (op.oldFile() == null || !op.oldFile().equalsIgnoreCase(op.newFile()))) {
                throw new IOException("A new JAR path is already occupied");
            }
        }
    }

    public static void abandon(Path gameDir, Item item) throws IOException {
        Path pending = PathSafety.janeDirectory(gameDir, "pending", item.syncId());
        deleteTree(pending);
        Path staging = PathSafety.janeDirectory(gameDir, "staging").resolve(item.syncId());
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteTree(staging);
    }

    private static UpdatePlan read(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 128 * 1024) {
            throw new IOException("Invalid pending metadata");
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray array = json.getAsJsonArray("operations");
            List<UpdatePlan.Operation> operations = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject op = element.getAsJsonObject();
                String oldFile = op.get("oldFile").isJsonNull() ? null : op.get("oldFile").getAsString();
                String oldHash = op.get("oldHash").isJsonNull() ? null : op.get("oldHash").getAsString();
                operations.add(new UpdatePlan.Operation(op.get("modId").getAsString(),
                        UpdatePlan.Kind.valueOf(op.get("operation").getAsString()), oldFile, oldHash,
                        op.get("newFile").getAsString(), op.get("expectedSha512").getAsString(), op.get("size").getAsLong()));
            }
            return new UpdatePlan(json.get("syncId").getAsString(), json.get("serverId").getAsString(),
                    json.get("backupTimestamp").getAsString(), operations);
        } catch (RuntimeException exception) {
            throw new IOException("Corrupt pending metadata", exception);
        }
    }

    private static void finish(Path gameDir, UpdatePlan plan) throws IOException {
        for (UpdatePlan.Operation op : plan.operations()) {
            Path installed = PathSafety.newJarInMods(gameDir, op.newFile());
            if (!Files.isRegularFile(installed, LinkOption.NOFOLLOW_LINKS) || Files.size(installed) != op.size()
                    || !Hashing.sha512(installed).equals(op.newHash())) throw new IOException("Installed JAR verification failed");
        }
        Path backup = PathSafety.janeDirectory(gameDir, "backups", plan.serverId(), plan.timestamp());
        if (!Files.isRegularFile(backup.resolve("backup.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup metadata is missing");
        }
        for (UpdatePlan.Operation op : plan.operations()) {
            if (op.kind() == UpdatePlan.Kind.REPLACE) {
                Path old = PathSafety.existingJarChild(backup, op.oldFile());
                if (!Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS) || !Hashing.sha512(old).equals(op.oldHash())) {
                    throw new IOException("Backup JAR verification failed");
                }
            }
        }
        Files.writeString(backup.resolve("success.marker"), "SUCCESS\n", StandardCharsets.UTF_8);
        prune(gameDir, plan.serverId());
        Path staging = PathSafety.janeDirectory(gameDir, "staging").resolve(plan.syncId());
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteTree(staging);
        deleteTree(PathSafety.janeDirectory(gameDir, "pending", plan.syncId()));
    }

    private static void prune(Path gameDir, String serverId) throws IOException {
        Path root = PathSafety.janeDirectory(gameDir, "backups", serverId);
        List<Path> completed;
        try (Stream<Path> dirs = Files.list(root)) {
            completed = dirs.filter(path -> path.getFileName().toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}"))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                    .filter(path -> Files.isRegularFile(path.resolve("success.marker"), LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        List<String> expired = BackupRetention.expired(completed.stream().map(path -> path.getFileName().toString()).toList(), 5);
        for (Path path : completed) if (expired.contains(path.getFileName().toString())) deleteTree(path);
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("Refusing symlink deletion");
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
