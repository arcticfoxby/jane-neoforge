package dev.modsbyfox.jane.neoforge;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** Dedicated-server transfer settings; absence uses the existing Minecraft port. */
public record JaneProviderConfig(Mode mode, int bindPort, int advertisedPort) {
    public enum Mode { AUTO, MINECRAFT, SEPARATE_PORT, DISABLED }

    private static final int MAX_BYTES = 64 * 1024;

    public JaneProviderConfig {
        if (mode == null) throw new IllegalArgumentException("Missing provider mode");
        if (mode == Mode.SEPARATE_PORT) {
            if (bindPort < 1 || bindPort > 65535 || advertisedPort < 1 || advertisedPort > 65535)
                throw new IllegalArgumentException("Invalid provider port");
        } else if (bindPort != 0 || advertisedPort != 0) {
            throw new IllegalArgumentException("Ports require SEPARATE_PORT mode");
        }
    }

    public static JaneProviderConfig read(Path gameDir) throws IOException {
        if (Files.isSymbolicLink(gameDir)) throw new IOException("Game directory is a symbolic link");
        Path game = gameDir.toRealPath();
        if (!gameDir.toAbsolutePath().normalize().equals(game))
            throw new IOException("Game directory resolves through a redirected path");
        Path config = childDirectoryIfPresent(game, "config");
        if (config == null) return new JaneProviderConfig(Mode.AUTO, 0, 0);
        Path jane = childDirectoryIfPresent(config, "jane");
        if (jane == null) return new JaneProviderConfig(Mode.AUTO, 0, 0);
        Path file = jane.resolve("provider.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            return new JaneProviderConfig(Mode.AUTO, 0, 0);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().equals(file) || Files.size(file) > MAX_BYTES)
            throw new IOException("Unsafe Jane provider config");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Jane provider config exceeds 64 KiB");
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Jane provider config is not UTF-8", exception);
        }
        return parse(content);
    }

    static JaneProviderConfig parse(String content) throws IOException {
        Mode mode = null;
        Integer bind = null;
        Integer advertised = null;
        Set<String> seen = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!seen.add(key)) throw new IOException("Duplicate Jane provider setting");
                switch (key) {
                    case "mode" -> {
                        if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid provider mode");
                        mode = Mode.valueOf(reader.nextString());
                    }
                    case "bindPort" -> bind = readPort(reader);
                    case "advertisedPort" -> advertised = readPort(reader);
                    default -> throw new IOException("Unknown Jane provider setting");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing provider config");
            if (mode == null) throw new IOException("Missing Jane provider mode");
            return new JaneProviderConfig(mode, bind == null ? 0 : bind, advertised == null ? 0 : advertised);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new IOException("Invalid Jane provider config", exception);
        }
    }

    private static int readPort(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Invalid Jane provider port");
        String value = reader.nextString();
        if (!value.matches("[0-9]{1,5}")) throw new IOException("Invalid Jane provider port");
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) throw new IOException("Invalid Jane provider port");
        return port;
    }

    private static Path childDirectoryIfPresent(Path parent, String name) throws IOException {
        Path child = parent.resolve(name);
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                || !child.toRealPath().equals(child)) throw new IOException("Unsafe Jane provider config directory");
        return child;
    }
}
