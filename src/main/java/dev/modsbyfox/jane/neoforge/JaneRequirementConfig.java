package dev.modsbyfox.jane.neoforge;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Optional, read-only explicit client requirement overrides for physical JARs. */
public final class JaneRequirementConfig {
    private static final int MAX_BYTES = 64 * 1024;
    private final Map<String, ClientRequirementClassifier.Override> byModId;
    private final Map<String, ClientRequirementClassifier.Override> byJar;

    private JaneRequirementConfig(Map<String, ClientRequirementClassifier.Override> byModId,
                                  Map<String, ClientRequirementClassifier.Override> byJar) {
        this.byModId = Map.copyOf(byModId);
        this.byJar = Map.copyOf(byJar);
    }

    /**
     * Reads {@code config/jane/requirements.json} inside this game instance.
     * Absence is equivalent to an empty config; malformed or escaping paths fail.
     */
    public static JaneRequirementConfig read(Path gameDir) throws IOException {
        Objects.requireNonNull(gameDir, "gameDir");
        if (Files.isSymbolicLink(gameDir)) {
            throw new IOException("Game directory is a symbolic link");
        }
        Path realGame = gameDir.toRealPath();
        if (!gameDir.toAbsolutePath().normalize().equals(realGame)) {
            throw new IOException("Game directory resolves through a redirected path");
        }
        Path config = checkedChildDirectory(realGame, "config");
        if (config == null) return empty();
        Path jane = checkedChildDirectory(config, "jane");
        if (jane == null) return empty();

        Path file = jane.resolve("requirements.json");
        BasicFileAttributes fileAttributes;
        try {
            fileAttributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return empty();
        }
        if (fileAttributes.isSymbolicLink() || !fileAttributes.isRegularFile()) {
            throw new IOException("Requirements config is not a regular file");
        }
        Path realFile = file.toRealPath();
        if (!jane.equals(realFile.getParent()) || !file.equals(realFile)) {
            throw new IOException("Requirements config escaped the game directory");
        }
        if (Files.size(realFile) > MAX_BYTES) {
            throw new IOException("Requirements config exceeds 64 KiB");
        }
        byte[] bytes;
        try (InputStream input = Channels.newInputStream(Files.newByteChannel(realFile,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Requirements config exceeds 64 KiB");
        }
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidEncoding) {
            throw new IOException("Requirements config is not UTF-8", invalidEncoding);
        }
        return parse(content);
    }

    /**
     * A REQUIRED override for any contained mod ID wins. A per-ID NOT_REQUIRED
     * override excludes a multi-mod JAR only when every ID has that override;
     * byJar targets the whole physical JAR directly.
     */
    public ClientRequirementClassifier.Override forJar(NeoForgePhysicalDiscovery.PhysicalJar jar) {
        Objects.requireNonNull(jar, "jar");
        ClientRequirementClassifier.Override fileOverride =
                byJar.get(jar.jar().getFileName().toString());
        if (fileOverride == ClientRequirementClassifier.Override.REQUIRED) {
            return fileOverride;
        }
        boolean everyIdNotRequired = !jar.modIds().isEmpty();
        for (String modId : jar.modIds()) {
            ClientRequirementClassifier.Override idOverride = byModId.get(modId);
            if (idOverride == ClientRequirementClassifier.Override.REQUIRED) {
                return idOverride;
            }
            if (idOverride != ClientRequirementClassifier.Override.NOT_REQUIRED) {
                everyIdNotRequired = false;
            }
        }
        if (fileOverride == ClientRequirementClassifier.Override.NOT_REQUIRED || everyIdNotRequired) {
            return ClientRequirementClassifier.Override.NOT_REQUIRED;
        }
        return ClientRequirementClassifier.Override.NONE;
    }

    private static Path checkedChildDirectory(Path parent, String name) throws IOException {
        Path child = parent.resolve(name);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IOException("Requirements config directory is not a regular directory");
        }
        Path real = child.toRealPath();
        if (!parent.equals(real.getParent()) || !child.equals(real)) {
            throw new IOException("Requirements config directory escaped the game directory");
        }
        return real;
    }

    private static JaneRequirementConfig empty() {
        return new JaneRequirementConfig(Map.of(), Map.of());
    }

    private static JaneRequirementConfig parse(String content) throws IOException {
        Map<String, ClientRequirementClassifier.Override> byModId = Map.of();
        Map<String, ClientRequirementClassifier.Override> byJar = Map.of();
        Set<String> fields = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new IOException("Requirements config must be an object");
            }
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) {
                    throw new IOException("Duplicate requirements config field");
                }
                switch (field) {
                    case "byModId" -> byModId = readOverrides(reader, true);
                    case "byJar" -> byJar = readOverrides(reader, false);
                    default -> throw new IOException("Unknown requirements config field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Trailing requirements config content");
            }
        }
        return new JaneRequirementConfig(byModId, byJar);
    }

    private static Map<String, ClientRequirementClassifier.Override> readOverrides(
            JsonReader reader, boolean modIds) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw new IOException("Requirements overrides must be an object");
        }
        Map<String, ClientRequirementClassifier.Override> entries = new HashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            try {
                if (modIds) {
                    if (!ManifestEntry.validId(key)) {
                        throw new IllegalArgumentException("Invalid mod ID");
                    }
                } else {
                    PathSafety.safeExistingJarName(key);
                }
            } catch (IllegalArgumentException invalidKey) {
                throw new IOException("Invalid requirements override key", invalidKey);
            }
            if (entries.containsKey(key)) {
                throw new IOException("Duplicate requirements override key");
            }
            if (reader.peek() != JsonToken.STRING) {
                throw new IOException("Requirements override must be a string");
            }
            String value = reader.nextString();
            ClientRequirementClassifier.Override override = switch (value) {
                case "REQUIRED" -> ClientRequirementClassifier.Override.REQUIRED;
                case "NOT_REQUIRED" -> ClientRequirementClassifier.Override.NOT_REQUIRED;
                default -> throw new IOException("Unknown requirements override value");
            };
            entries.put(key, override);
        }
        reader.endObject();
        return Map.copyOf(entries);
    }
}
