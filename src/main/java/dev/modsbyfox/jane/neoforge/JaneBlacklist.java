package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** An operator-provided, byte-for-byte copy of a physical JAR opts it out of client sync. */
final class JaneBlacklist {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private final Path directory;

    private JaneBlacklist(Path directory) {
        this.directory = directory;
    }

    static JaneBlacklist open(Path gameDir) throws IOException {
        Path directory = PathSafety.janeDirectory(gameDir, "blacklist");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Jane blacklist is not a directory");
        return new JaneBlacklist(directory);
    }

    boolean matches(Path jar, long size, String sha512) throws IOException {
        checkDirectory();
        Path copy = directory.resolve(jar.getFileName()).normalize();
        if (!directory.equals(copy.getParent()))
            throw new IOException("Jane blacklist JAR escaped its directory");
        if (!Files.exists(copy, LinkOption.NOFOLLOW_LINKS)) return false;
        checkCopy(copy);
        long copySize = Files.size(copy);
        if (copySize != size) {
            LOGGER.warn("Jane blacklist copy differs from mods JAR; file={}", jar.getFileName());
            return false;
        }
        FileTime modified = Files.getLastModifiedTime(copy, LinkOption.NOFOLLOW_LINKS);
        String copyHash = Hashing.sha512(copy);
        checkCopy(copy);
        if (Files.size(copy) != copySize
                || !Files.getLastModifiedTime(copy, LinkOption.NOFOLLOW_LINKS).equals(modified)) {
            throw new IOException("Jane blacklist JAR changed while hashing: " + jar.getFileName());
        }
        if (!sha512.equals(copyHash)) {
            LOGGER.warn("Jane blacklist copy differs from mods JAR; file={}", jar.getFileName());
            return false;
        }
        return true;
    }

    private void checkCopy(Path copy) throws IOException {
        checkDirectory();
        PathSafety.rejectReparsePoint(copy);
        if (!Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)
                || !copy.toRealPath().getParent().equals(directory)) {
            throw new IOException("Unsafe Jane blacklist JAR: " + copy.getFileName());
        }
    }

    private void checkDirectory() throws IOException {
        PathSafety.rejectReparsePoint(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(directory)) {
            throw new IOException("Jane blacklist directory changed while preparing the manifest");
        }
    }
}
