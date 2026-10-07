package dev.modsbyfox.jane.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local acknowledgement only; no client inventory is stored or sent to the server. */
public final class ClientReviewStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private static final int MAX_BYTES = 16 * 1024;
    private ClientReviewStore() { }

    public static boolean reviewRequired(Path gameDir, String serverId, String fingerprint) {
        validate(serverId, fingerprint);
        try {
            Path file = file(gameDir, serverId);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return true;
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_BYTES) throw new IOException("Invalid review record");
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw new IOException("Client review record too large");
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException exception) {
                throw new IOException("Invalid client review encoding", exception);
            }
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            if (json.size() != 4 || json.get("schemaVersion").getAsInt() != 1
                    || !serverId.equals(json.get("serverId").getAsString())
                    || !"DIRECT_JOIN".equals(json.get("action").getAsString()))
                throw new IOException("Invalid review record");
            String saved = json.get("fingerprint").getAsString();
            if (!saved.matches("[0-9a-f]{128}")) throw new IOException("Invalid review fingerprint");
            return !fingerprint.equals(saved);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Jane client review record unavailable or invalid for server {}",
                    serverId.substring(0, 12));
            return true;
        }
    }

    public static void acknowledge(Path gameDir, String serverId, String fingerprint) throws IOException {
        validate(serverId, fingerprint);
        Path file = file(gameDir, serverId);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Invalid client review record target");
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("serverId", serverId);
        json.addProperty("fingerprint", fingerprint);
        json.addProperty("action", "DIRECT_JOIN");
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Client review record too large");
        Path temp = Files.createTempFile(file.getParent(), "review-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path file(Path gameDir, String serverId) throws IOException {
        return PathSafety.janeDirectory(gameDir, "client-review").resolve(serverId + ".json");
    }

    private static void validate(String serverId, String fingerprint) {
        if (serverId == null || !serverId.matches("[0-9a-f]{64}")
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{128}"))
            throw new IllegalArgumentException("Invalid review identity");
    }
}
