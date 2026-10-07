package dev.modsbyfox.jane.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public final class HashCache {
    private record Entry(long size, long modified, String sha512) { }
    private final Path file;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Gson gson = new Gson();

    public HashCache(Path gameDir) throws IOException {
        this.file = PathSafety.janeDirectory(gameDir, "cache").resolve("hash-cache.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Hash cache is a symlink");
        if (!Files.isRegularFile(file)) return;
        try {
            if (Files.size(file) > 1024 * 1024) throw new IOException("Hash cache too large");
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> item : json.entrySet()) {
                JsonObject value = item.getValue().getAsJsonObject();
                String hash = value.get("sha512").getAsString();
                if (hash.matches("[0-9a-f]{128}")) {
                    entries.put(item.getKey(), new Entry(value.get("size").getAsLong(), value.get("modified").getAsLong(), hash));
                }
            }
        } catch (RuntimeException | IOException ignored) {
            entries.clear(); // A damaged cache only costs a rehash.
        }
    }

    public synchronized String hash(Path jar) throws IOException {
        Path path = jar.toRealPath();
        String key = path.toString();
        long size = Files.size(path);
        long modified = Files.getLastModifiedTime(path).toMillis();
        Entry cached = entries.get(key);
        if (cached != null && cached.size == size && cached.modified == modified) return cached.sha512;
        String hash = Hashing.sha512(path);
        // Detect replacement while the file was being read.
        if (Files.size(path) != size || Files.getLastModifiedTime(path).toMillis() != modified) {
            throw new IOException("JAR changed while hashing");
        }
        entries.put(key, new Entry(size, modified, hash));
        try {
            save();
        } catch (IOException ignored) {
            // The cache is optional; the freshly calculated hash remains authoritative.
        }
        return hash;
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "hash-cache-", ".tmp");
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            JsonObject value = new JsonObject();
            value.addProperty("size", item.getValue().size);
            value.addProperty("modified", item.getValue().modified);
            value.addProperty("sha512", item.getValue().sha512);
            json.add(item.getKey(), value);
        }
        try {
            Files.writeString(temp, gson.toJson(json), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
