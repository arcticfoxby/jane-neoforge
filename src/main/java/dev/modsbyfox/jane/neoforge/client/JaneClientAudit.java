package dev.modsbyfox.jane.neoforge.client;

import dev.modsbyfox.jane.core.SyncDecisionAudit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import net.neoforged.fml.ModList;

/** Adds the installed NeoForge mod version to loader-neutral decision records. */
public final class JaneClientAudit {
    private JaneClientAudit() { }

    public static void record(Path gameDir, String event, Map<String, String> fields) throws IOException {
        ModList mods = ModList.get();
        if (mods == null) throw new IOException("Jane mod metadata is unavailable");
        String version = mods.getModContainerById("jane")
                .orElseThrow(() -> new IOException("Jane mod metadata is unavailable"))
                .getModInfo().getVersion().toString();
        SyncDecisionAudit.record(gameDir, version, event, fields);
    }
}
