package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.core.ClientDisablePlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/** Launches only Jane's generated helper after a final staged-file verification. */
final class UpdateLauncher {
    private UpdateLauncher() { }

    static void launch(Minecraft client, Path gameDir, UpdatePlan plan) throws IOException {
        PendingRecovery.verifyForLaunch(gameDir, plan);
        Path pending = PathSafety.janeDirectory(gameDir, "pending", plan.syncId());
        start(client, gameDir, pending.resolve("update.bat"), "Jane updater",
                "jane\\pending\\" + plan.syncId() + "\\update.bat");
    }

    static void launchDisable(Minecraft client, Path gameDir, ClientDisablePlan plan) throws IOException {
        Path folder = PathSafety.janeDirectory(gameDir, "disabled", plan.serverId(), plan.timestamp());
        start(client, gameDir, folder.resolve("disable.bat"), "Jane client disable",
                "jane\\disabled\\" + plan.serverId() + "\\" + plan.timestamp() + "\\disable.bat");
    }

    private static void start(Minecraft client, Path gameDir, Path script, String title,
                              String relativeScript) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"))
            throw new IOException("Automatic file operations require Windows");
        if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(script))
            throw new IOException("Jane helper script is missing");
        // All path segments come from validated UUID, server ID or timestamp values.
        String command = "start \"" + title + "\" cmd.exe /c " + relativeScript;
        Process starter = new ProcessBuilder("cmd.exe", "/c", command).directory(gameDir.toFile()).start();
        try {
            if (!starter.waitFor(5, TimeUnit.SECONDS) || starter.exitValue() != 0)
                throw new IOException("Windows did not start Jane updater");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Jane updater launch interrupted", exception);
        }
        client.stop();
    }
}
