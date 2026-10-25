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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PendingRecovery {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private static final long MAX_UPDATE_LOG_BYTES = 8L * 1024 * 1024;
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
        verifySourceFiles(gameDir, old);
        String timestamp = BackupTimestamp.next(gameDir, old.serverId());
        UpdatePlan retry = new UpdatePlan(old.syncId(), old.serverId(), timestamp,
                old.operations(), old.cleanupEntries(), old.dependencyGuards(),
                old.stagedGraphFingerprint());
        PendingStore.createRetry(gameDir, old, retry);
        Path pending = PathSafety.janeDirectory(gameDir, "pending", old.syncId());
        verifyForLaunch(gameDir, retry);
        Files.deleteIfExists(pending.resolve("failed.marker"));
        return retry;
    }

    public static void verifyForLaunch(Path gameDir, UpdatePlan plan) throws IOException {
        Path pending = PathSafety.janeDirectory(gameDir, "pending", plan.syncId());
        if (!plan.equals(read(pending.resolve("pending.json"))))
            throw new IOException("Pending plan changed after confirmation");
        Path script = pending.resolve("update.bat");
        PathSafety.rejectReparsePoint(script);
        if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(script, StandardCharsets.UTF_8)
                .equals(WindowsBatch.generate(plan, ProcessHandle.current().pid())))
            throw new IOException("Pending updater script changed after confirmation");
        verifySourceFiles(gameDir, plan);
        if (!plan.cleanupEntries().isEmpty()) {
            Path disabled = PathSafety.janeDirectory(gameDir, "disabled", plan.serverId())
                    .resolve(plan.timestamp());
            if (Files.exists(disabled, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Client cleanup backup target already exists");
            verifyCleanupMetadata(pending.resolve("disabled.json"), plan);
        }
    }

    private static void verifySourceFiles(Path gameDir, UpdatePlan plan) throws IOException {
        if (!plan.dependencyGuards().isEmpty()) {
            java.util.Set<String> guarded = plan.dependencyGuards().stream()
                    .map(ClientDisablePlan.DependencyGuard::filename)
                    .collect(java.util.stream.Collectors.toSet());
            if (!PathSafety.topLevelJarNames(gameDir).equals(guarded))
                throw new IOException("Current mods JAR inventory differs from approved dependency snapshot");
        }
        for (ClientDisablePlan.DependencyGuard guard : plan.dependencyGuards()) {
            Path source = PathSafety.existingJarNamedInMods(gameDir, guard.filename());
            if (!Hashing.sha512(source).equals(guard.sha512()))
                throw new IOException("A client dependency JAR changed after confirmation");
        }
        Path staging = plan.operations().isEmpty() ? null
                : PathSafety.janeDirectory(gameDir, "staging", plan.syncId());
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
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && (op.oldFile() == null || !op.oldFile().equalsIgnoreCase(op.newFile()))) {
                throw new IOException("A new JAR path is already occupied");
            }
        }
        if (!plan.cleanupEntries().isEmpty()) {
            for (ClientDisablePlan.Entry entry : plan.cleanupEntries()) {
                Path source = PathSafety.existingJarNamedInMods(gameDir, entry.filename());
                if (!Hashing.sha512(source).equals(entry.sha512()))
                    throw new IOException("Client cleanup JAR changed after confirmation");
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
        PathSafety.rejectReparsePoint(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 128 * 1024) {
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
            List<ClientDisablePlan.Entry> cleanup = new ArrayList<>();
            JsonArray cleanupArray = json.getAsJsonArray("cleanupEntries");
            if (cleanupArray != null) for (JsonElement element : cleanupArray) {
                cleanup.add(readCleanupEntry(element.getAsJsonObject()));
            }
            List<ClientDisablePlan.DependencyGuard> guards = new ArrayList<>();
            JsonArray guardArray = json.getAsJsonArray("dependencyGuards");
            if (guardArray != null) for (JsonElement element : guardArray) {
                JsonObject guard = element.getAsJsonObject();
                guards.add(new ClientDisablePlan.DependencyGuard(guard.get("filename").getAsString(),
                        guard.get("sha512").getAsString()));
            }
            return new UpdatePlan(json.get("syncId").getAsString(), json.get("serverId").getAsString(),
                    json.get("backupTimestamp").getAsString(), operations, cleanup, guards,
                    json.has("stagedGraphFingerprint")
                            ? json.get("stagedGraphFingerprint").getAsString() : null);
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
        if (!plan.cleanupEntries().isEmpty()) {
            Path disabled = PathSafety.janeDirectory(gameDir, "disabled", plan.serverId(), plan.timestamp());
            verifyCleanupMetadata(disabled.resolve("disabled.json"), plan);
            if (!Files.isRegularFile(disabled.resolve("success.marker"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(disabled.resolve("disable.log"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Client cleanup record is incomplete");
            for (ClientDisablePlan.Entry entry : plan.cleanupEntries()) {
                Path saved = PathSafety.existingJarChild(disabled, entry.filename());
                PathSafety.rejectReparsePoint(saved);
                if (!Files.isRegularFile(saved, LinkOption.NOFOLLOW_LINKS)
                        || !Hashing.sha512(saved).equals(entry.sha512())
                        || Files.exists(gameDir.resolve("mods").resolve(entry.filename()), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Client cleanup backup verification failed");
            }
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
        preserveUpdateLog(gameDir, plan, backup);
        Path marker = backup.resolve("success.marker");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Unsafe backup success marker");
        Files.writeString(marker, "SUCCESS\n", StandardCharsets.UTF_8);
        prune(gameDir, plan.serverId());
        if (!plan.operations().isEmpty()) {
            Path staging = PathSafety.janeDirectory(gameDir, "staging").resolve(plan.syncId());
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteTree(staging);
        }
        deleteTree(PathSafety.janeDirectory(gameDir, "pending", plan.syncId()));
    }

    private static void verifyCleanupMetadata(Path file, UpdatePlan plan) throws IOException {
        PathSafety.rejectReparsePoint(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 128 * 1024)
            throw new IOException("Invalid client cleanup metadata");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!plan.serverId().equals(json.get("serverId").getAsString())
                    || !plan.timestamp().equals(json.get("timestamp").getAsString()))
                throw new IOException("Client cleanup identity mismatch");
            List<ClientDisablePlan.Entry> entries = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("entries")) {
                entries.add(readCleanupEntry(element.getAsJsonObject()));
            }
            if (!plan.cleanupEntries().equals(entries)) throw new IOException("Client cleanup selection changed");
            List<ClientDisablePlan.DependencyGuard> guards = new ArrayList<>();
            JsonArray guardArray = json.getAsJsonArray("dependencyGuards");
            if (guardArray != null) for (JsonElement element : guardArray) {
                JsonObject guard = element.getAsJsonObject();
                guards.add(new ClientDisablePlan.DependencyGuard(guard.get("filename").getAsString(),
                        guard.get("sha512").getAsString()));
            }
            if (!plan.dependencyGuards().equals(guards))
                throw new IOException("Client dependency snapshot changed");
            String stagedFingerprint = json.has("stagedGraphFingerprint")
                    ? json.get("stagedGraphFingerprint").getAsString() : null;
            if (!java.util.Objects.equals(plan.stagedGraphFingerprint(), stagedFingerprint))
                throw new IOException("Staged dependency proof changed");
        } catch (RuntimeException exception) {
            throw new IOException("Invalid client cleanup metadata", exception);
        }
    }

    private static ClientDisablePlan.Entry readCleanupEntry(JsonObject json) {
        String modId = json.get("modId").getAsString();
        ClientCompatibilityReport.Kind kind = json.has("kind")
                ? ClientCompatibilityReport.Kind.valueOf(json.get("kind").getAsString())
                : ClientCompatibilityReport.Kind.EXPLICIT_CLIENT;
        JsonArray ids = json.getAsJsonArray("containedModIds");
        List<String> contained = new ArrayList<>();
        if (ids == null) contained.add(modId);
        else for (JsonElement id : ids) contained.add(id.getAsString());
        return new ClientDisablePlan.Entry(modId, json.get("displayName").getAsString(),
                json.get("version").getAsString(), json.get("filename").getAsString(),
                json.get("sha512").getAsString(), kind, contained);
    }

    /** Keep the updater's original bounded log before verified pending files are removed. */
    private static void preserveUpdateLog(Path gameDir, UpdatePlan plan, Path backup) throws IOException {
        Path source = PathSafety.janeDirectory(gameDir, "pending", plan.syncId()).resolve("update.log");
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.warn("Jane verified update has no CMD log syncId={}; continuing recovery", plan.syncId());
            return;
        }
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.size(source) > MAX_UPDATE_LOG_BYTES)
            throw new IOException("Unsafe or oversized pending update log");
        Path target = backup.resolve("update.log");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) > MAX_UPDATE_LOG_BYTES))
            throw new IOException("Unsafe or oversized backup update log");
        Path temporary = Files.createTempFile(backup, "update-log-", ".tmp");
        try {
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                 OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE,
                         LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024];
                long copied = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    copied += count;
                    if (copied > MAX_UPDATE_LOG_BYTES) throw new IOException("Pending update log grew beyond limit");
                    output.write(buffer, 0, count);
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
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
