package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared capability and exact-file checks for both Provider transports. */
public final class ServerProviderCore implements AutoCloseable {
    public record File(ManifestEntry entry, Path path) { }
    public record Access(byte status, Lease lease) { }

    public final class Lease implements AutoCloseable {
        private final String token;
        private final File file;
        private final FileChannel channel;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(String token, File file, FileChannel channel) {
            this.token = token;
            this.file = file;
            this.channel = channel;
        }

        public ManifestEntry entry() { return file.entry(); }
        public FileChannel channel() { return channel; }
        @Override public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                try { channel.close(); } finally { tokens.release(token); }
            }
        }
    }

    private final Path gameDir;
    private final Map<String, File> files;
    private final ServerProviderTokens tokens;

    public ServerProviderCore(Path gameDir, Map<String, File> files, Clock clock) throws IOException {
        this.gameDir = gameDir;
        this.files = Map.copyOf(files);
        for (var item : this.files.entrySet()) {
            if (item.getValue() == null || item.getValue().entry() == null || item.getValue().path() == null
                    || !item.getKey().equals(item.getValue().entry().sha512())) {
                throw new IOException("Provider hash mapping differs from manifest");
            }
        }
        this.tokens = new ServerProviderTokens(clock);
    }

    public String issue(RequiredManifest manifest) {
        Set<String> hashes = manifest.entries().stream().map(ManifestEntry::sha512)
                .collect(java.util.stream.Collectors.toSet());
        if (!files.keySet().containsAll(hashes)) throw new IllegalStateException("Provider manifest files are missing");
        return tokens.issue(hashes);
    }

    public Access open(String token, String hash) {
        if (!tokens.known(token)) return new Access(ServerProviderWire.UNAUTHORIZED, null);
        if (!tokens.acquire(token, hash)) return new Access(ServerProviderWire.NOT_ALLOWED, null);
        File file = files.get(hash);
        if (file == null) {
            tokens.release(token);
            return new Access(ServerProviderWire.NOT_ALLOWED, null);
        }
        try {
            Path safe = PathSafety.existingJarInMods(gameDir, file.path());
            FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try {
                if (channel.size() != file.entry().fileSize() || !hash(channel).equals(hash)) {
                    channel.close();
                    tokens.release(token);
                    return new Access(ServerProviderWire.STALE, null);
                }
                channel.position(0);
                return new Access(ServerProviderWire.OK, new Lease(token, file, channel));
            } catch (IOException | RuntimeException exception) {
                channel.close();
                throw exception;
            }
        } catch (IOException | RuntimeException exception) {
            tokens.release(token);
            return new Access(ServerProviderWire.STALE, null);
        }
    }

    private static String hash(FileChannel channel) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buffer = new byte[64 * 1024];
            var stream = Channels.newInputStream(channel);
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    @Override public void close() { tokens.clear(); }
}
