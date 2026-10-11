package dev.modsbyfox.jane.neoforge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Local operator diagnostic; the required manifest is never read from this file. */
final class DiscoveredModsStore {
    private DiscoveredModsStore() { }

    static void write(Path gameDir, String janeVersion, List<ServerManifest.Classified> classified)
            throws IOException {
        if (janeVersion == null || !janeVersion.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))
            throw new IOException("Invalid Jane version for discovery diagnostic");
        if (Files.isSymbolicLink(gameDir))
            throw new IOException("Game directory is a symbolic link");
        Path root = gameDir.toRealPath();
        if (!gameDir.toAbsolutePath().normalize().equals(root))
            throw new IOException("Game directory resolves through a redirected path");
        Path config = childDirectory(root, "config");
        Path jane = childDirectory(config, "jane");
        Path target = jane.resolve("discovered-mods.json");
        checkTarget(target);

        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 2);
        document.addProperty("janeVersion", janeVersion);
        JsonArray mods = new JsonArray();
        for (ServerManifest.Classified item : classified) {
            NeoForgePhysicalDiscovery.PhysicalJar physical = item.physicalJar();
            ClientRequirementClassifier.Result decision = item.decision();
            JsonObject mod = new JsonObject();
            mod.addProperty("modId", physical.canonicalId());
            JsonArray modIds = new JsonArray();
            physical.modIds().forEach(modIds::add);
            mod.add("modIds", modIds);
            mod.addProperty("displayName", physical.displayName());
            mod.addProperty("version", physical.version());
            mod.addProperty("fileName", physical.jar().getFileName().toString());
            mod.addProperty("fmlEvidenceComplete", physical.fmlEvidence().complete());
            mod.addProperty("analysisComplete", decision.analysisComplete());
            mod.addProperty("clientRequirement", decision.requirement().name());
            mod.addProperty("syncDecision", decision.includeInClientManifest() ? "SYNC" : "EXCLUDE");
            mod.addProperty("reason", decision.reason());
            if (item.sha512() != null) {
                mod.addProperty("fileSize", item.size());
                mod.addProperty("sha512", item.sha512());
            }
            mods.add(mod);
        }
        document.add("mods", mods);

        Path temporary = Files.createTempFile(jane, ".discovered-mods-", ".tmp");
        try {
            Files.writeString(temporary,
                    new GsonBuilder().setPrettyPrinting().create().toJson(document) + "\n",
                    StandardCharsets.UTF_8);
            // Recheck both paths before replacing an existing diagnostic.
            if (!jane.toRealPath().equals(jane) || Files.isSymbolicLink(jane))
                throw new IOException("Jane diagnostic directory changed during write");
            checkTarget(target);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path childDirectory(Path parent, String name) throws IOException {
        Path child = parent.resolve(name);
        if (Files.isSymbolicLink(child)) throw new IOException("Jane diagnostic directory is a symlink");
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(child);
        Path real = child.toRealPath();
        if (!real.getParent().equals(parent))
            throw new IOException("Jane diagnostic directory escaped current instance");
        return real;
    }

    private static void checkTarget(Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Jane discovery diagnostic target is unsafe");
    }
}
