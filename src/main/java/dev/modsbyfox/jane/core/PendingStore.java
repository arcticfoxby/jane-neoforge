package dev.modsbyfox.jane.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.core.WindowsBatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class PendingStore {
    private PendingStore() { }

    public static Path create(Path gameDir, UpdatePlan plan) throws IOException {
        Path expected = PathSafety.janeDirectory(gameDir, "pending").resolve(plan.syncId());
        boolean existed = Files.exists(expected, java.nio.file.LinkOption.NOFOLLOW_LINKS);
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
        JsonObject backup = new JsonObject();
        backup.addProperty("timestamp", plan.timestamp());
        backup.addProperty("serverId", plan.serverId());
        backup.add("changes", backupOperations);
        var gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        String pretty = gson.toJson(json);
        for (String name : new String[]{"backup.json", "update.bat", "pending.json"}) {
            if (Files.isSymbolicLink(pending.resolve(name))) throw new IOException("Pending file is a symlink");
        }
        try {
            Files.writeString(pending.resolve("backup.json"), gson.toJson(backup), StandardCharsets.UTF_8);
            Files.writeString(pending.resolve("update.bat"), WindowsBatch.generate(plan, ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            Files.writeString(pending.resolve("pending.json"), pretty, StandardCharsets.UTF_8);
            return pending;
        } catch (IOException | RuntimeException exception) {
            if (!existed) {
                try {
                    Files.deleteIfExists(pending.resolve("pending.json"));
                    Files.deleteIfExists(pending.resolve("backup.json"));
                    Files.deleteIfExists(pending.resolve("update.bat"));
                    Files.deleteIfExists(pending);
                } catch (IOException cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            throw exception;
        }
    }
}
