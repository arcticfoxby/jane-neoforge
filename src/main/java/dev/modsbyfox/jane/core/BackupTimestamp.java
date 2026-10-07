package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class BackupTimestamp {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private BackupTimestamp() { }

    public static String next(Path gameDir, String serverId) throws IOException {
        if (serverId == null || !serverId.matches("[0-9a-f]{64}")) throw new IOException("Invalid server ID");
        Path parent = PathSafety.janeDirectory(gameDir, "backups", serverId);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 60; i++) {
            String stamp = now.plusSeconds(i).format(FORMAT);
            if (!Files.exists(parent.resolve(stamp), LinkOption.NOFOLLOW_LINKS)) return stamp;
        }
        throw new IOException("No free backup timestamp");
    }
}
