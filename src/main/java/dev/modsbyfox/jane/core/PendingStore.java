package dev.modsbyfox.jane.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.core.WindowsBatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public final class PendingStore {
    private PendingStore() { }

    public static Path create(Path gameDir, UpdatePlan plan) throws IOException {
        return create(gameDir, plan, null);
    }

    /** Only an explicit recovery action may replace an uncompleted plan's timestamp. */
    static Path createRetry(Path gameDir, UpdatePlan previous, UpdatePlan retry) throws IOException {
        if (previous == null || retry == null || previous.timestamp().equals(retry.timestamp())
                || !previous.syncId().equals(retry.syncId())
                || !previous.serverId().equals(retry.serverId())
                || !previous.operations().equals(retry.operations())
                || !previous.cleanupEntries().equals(retry.cleanupEntries())
                || !previous.dependencyGuards().equals(retry.dependencyGuards())
                || !java.util.Objects.equals(previous.stagedGraphFingerprint(), retry.stagedGraphFingerprint()))
            throw new IOException("Retry changed approved update contents");
        return create(gameDir, retry, previous);
    }

    private static Path create(Path gameDir, UpdatePlan plan, UpdatePlan retryPrevious) throws IOException {
        Path expected = PathSafety.janeDirectory(gameDir, "pending").resolve(plan.syncId());
        boolean existed = Files.exists(expected, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (retryPrevious != null && !existed)
            throw new IOException("Retry pending plan is missing");
        Path pending = PathSafety.janeDirectory(gameDir, "pending", plan.syncId());
        JsonObject json = new JsonObject();
        json.addProperty("syncId", plan.syncId());
        json.addProperty("timestamp", Instant.now().toString());
        json.addProperty("backupTimestamp", plan.timestamp());
        json.addProperty("serverId", plan.serverId());
        json.addProperty("status", "READY");
        JsonArray operations = new JsonArray();
        JsonArray backupOperations = new JsonArray();
        for (UpdatePlan.Operation op : plan.operations()) {
            JsonObject item = new JsonObject();
            item.addProperty("operation", op.kind().name());
            item.addProperty("modId", op.modId());
            if (op.oldFile() == null) item.add("oldFile", com.google.gson.JsonNull.INSTANCE);
            else item.addProperty("oldFile", op.oldFile());
            if (op.oldHash() == null) item.add("oldHash", com.google.gson.JsonNull.INSTANCE);
            else item.addProperty("oldHash", op.oldHash());
            item.addProperty("newFile", op.newFile());
            item.addProperty("expectedSha512", op.newHash());
            item.addProperty("size", op.size());
            item.addProperty("status", "READY");
            operations.add(item);
            backupOperations.add(item.deepCopy());
        }
        json.add("operations", operations);
        JsonArray cleanup = new JsonArray();
        for (ClientDisablePlan.Entry entry : plan.cleanupEntries()) {
            JsonObject item = new JsonObject();
            item.addProperty("modId", entry.modId());
            item.addProperty("displayName", entry.displayName());
            item.addProperty("version", entry.version());
            item.addProperty("filename", entry.filename());
            item.addProperty("sha512", entry.sha512());
            item.addProperty("kind", entry.kind().name());
            JsonArray modIds = new JsonArray();
            entry.containedModIds().forEach(modIds::add);
            item.add("containedModIds", modIds);
            cleanup.add(item);
        }
        json.add("cleanupEntries", cleanup);
        JsonArray guards = new JsonArray();
        for (ClientDisablePlan.DependencyGuard guard : plan.dependencyGuards()) {
            JsonObject item = new JsonObject();
            item.addProperty("filename", guard.filename());
            item.addProperty("sha512", guard.sha512());
            guards.add(item);
        }
        json.add("dependencyGuards", guards);
        if (plan.stagedGraphFingerprint() != null)
            json.addProperty("stagedGraphFingerprint", plan.stagedGraphFingerprint());
        JsonObject backup = new JsonObject();
        backup.addProperty("timestamp", plan.timestamp());
        backup.addProperty("serverId", plan.serverId());
        backup.add("changes", backupOperations);
        backup.add("cleanupEntries", cleanup.deepCopy());
        backup.add("dependencyGuards", guards.deepCopy());
        if (plan.stagedGraphFingerprint() != null)
            backup.addProperty("stagedGraphFingerprint", plan.stagedGraphFingerprint());
        JsonObject disabled = new JsonObject();
        disabled.addProperty("schemaVersion", 1);
        disabled.addProperty("serverId", plan.serverId());
        disabled.addProperty("timestamp", plan.timestamp());
        disabled.add("entries", cleanup.deepCopy());
        disabled.add("dependencyGuards", guards.deepCopy());
        if (plan.stagedGraphFingerprint() != null)
            disabled.addProperty("stagedGraphFingerprint", plan.stagedGraphFingerprint());
        var gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        String pretty = gson.toJson(json);
        if (existed) {
            Path previous = pending.resolve("pending.json");
            PathSafety.rejectReparsePoint(previous);
            if (!Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS) || Files.size(previous) > 128 * 1024)
                throw new IOException("Existing pending plan cannot be verified");
            try {
                JsonObject saved = JsonParser.parseString(Files.readString(previous, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!plan.syncId().equals(saved.get("syncId").getAsString())
                        || !plan.serverId().equals(saved.get("serverId").getAsString())
                        || !operations.equals(saved.getAsJsonArray("operations")))
                    throw new IOException("Existing pending plan differs from approved update");
                String savedTimestamp = saved.get("backupTimestamp").getAsString();
                boolean sameTimestamp = plan.timestamp().equals(savedTimestamp);
                JsonArray savedCleanup = saved.getAsJsonArray("cleanupEntries");
                JsonArray savedGuards = saved.getAsJsonArray("dependencyGuards");
                // Previous releases did not record empty cleanup fields.
                if (savedCleanup == null) savedCleanup = new JsonArray();
                if (savedGuards == null) savedGuards = new JsonArray();
                boolean sameApproval = cleanup.equals(savedCleanup)
                        && guards.equals(savedGuards)
                        && java.util.Objects.equals(json.get("stagedGraphFingerprint"),
                                saved.get("stagedGraphFingerprint"));
                boolean notStarted = "READY".equals(saved.get("status").getAsString())
                        && !Files.exists(pending.resolve("success.marker"), LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(pending.resolve("failed.marker"), LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(pending.resolve("update.log"), LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(pending.resolve("pid.txt"), LinkOption.NOFOLLOW_LINKS);
                // Download staging persists an install-only plan before the final confirmation.
                // It may be promoted exactly once while no updater has started.
                boolean initialInstallPromotion = sameTimestamp && !plan.operations().isEmpty()
                        && !plan.cleanupEntries().isEmpty()
                        && notStarted
                        && savedCleanup.isEmpty()
                        && savedGuards.isEmpty()
                        && !saved.has("stagedGraphFingerprint");
                boolean authorizedRetry = retryPrevious != null
                        && retryPrevious.timestamp().equals(savedTimestamp)
                        && sameApproval && "READY".equals(saved.get("status").getAsString())
                        && !Files.exists(pending.resolve("success.marker"), LinkOption.NOFOLLOW_LINKS);
                if (!(sameTimestamp && sameApproval && notStarted)
                        && !initialInstallPromotion && !authorizedRetry)
                    throw new IOException("Existing pending plan differs from approved update");
            } catch (RuntimeException exception) {
                throw new IOException("Existing pending plan cannot be verified", exception);
            }
        }
        if (Files.exists(pending.resolve("success.marker"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Completed pending plan cannot be changed");
        for (String name : new String[]{"backup.json", "disabled.json", "update.bat", "pending.json"}) {
            PathSafety.rejectReparsePoint(pending.resolve(name));
        }
        try {
            writeAtomically(pending.resolve("backup.json"), gson.toJson(backup));
            if (!plan.cleanupEntries().isEmpty())
                writeAtomically(pending.resolve("disabled.json"), gson.toJson(disabled));
            writeAtomically(pending.resolve("update.bat"), WindowsBatch.generate(plan, ProcessHandle.current().pid()));
            writeAtomically(pending.resolve("pending.json"), pretty);
            return pending;
        } catch (IOException | RuntimeException exception) {
            if (!existed) {
                try {
                    Files.deleteIfExists(pending.resolve("pending.json"));
                    Files.deleteIfExists(pending.resolve("backup.json"));
                    Files.deleteIfExists(pending.resolve("disabled.json"));
                    Files.deleteIfExists(pending.resolve("update.bat"));
                    Files.deleteIfExists(pending);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    private static void writeAtomically(Path target, String value) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "jane-plan-", ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
