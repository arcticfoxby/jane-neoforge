package dev.modsbyfox.jane.neoforge.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.ModrinthDownload;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.ResolutionPlan;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

/** Exact-file Modrinth lookup for Minecraft 1.21.1 and NeoForge. */
final class ModrinthClient {
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final Pattern PROJECT_ID = Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final String USER_AGENT = "modsbyfox/Jane-NeoForge";

    @FunctionalInterface interface JsonSource {
        Optional<JsonObject> read(URI uri) throws IOException, InterruptedException;
    }

    record Match(Optional<ResolutionPlan.Source> source, ClientInstallCategory category) { }

    private final HttpClient http;
    private final JsonSource source;

    ModrinthClient() { this(null); }

    ModrinthClient(JsonSource source) {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.source = source == null ? this::fetch : source;
    }

    Match find(ManifestEntry target) throws IOException, InterruptedException {
        URI exactFile = URI.create("https://api.modrinth.com/v2/version_file/" + target.sha512()
                + "?algorithm=sha512");
        try {
            Optional<JsonObject> response = source.read(exactFile);
            if (response.isEmpty()) return new Match(Optional.empty(), ClientInstallCategory.SERVER_REQUIRED);
            JsonObject version = response.get();
            if (!contains(version.getAsJsonArray("game_versions"), "1.21.1")
                    || !contains(version.getAsJsonArray("loaders"), "neoforge"))
                return new Match(Optional.empty(), ClientInstallCategory.SERVER_REQUIRED);
            JsonArray files = version.getAsJsonArray("files");
            if (files == null) throw new IOException("Modrinth response has no files");
            for (JsonElement element : files) {
                JsonObject file = element.getAsJsonObject();
                JsonObject hashes = file.getAsJsonObject("hashes");
                if (hashes == null || !hashes.has("sha512")
                        || !target.sha512().equals(hashes.get("sha512").getAsString())
                        || target.fileSize() != file.get("size").getAsLong()) continue;
                String name = PathSafety.safeJarName(file.get("filename").getAsString());
                URI download = URI.create(file.get("url").getAsString());
                ResolutionPlan.Source match = new ResolutionPlan.Source(name, download, target.fileSize());
                return new Match(Optional.of(match), category(version));
            }
            throw new IOException("Modrinth exact hash response has no matching file");
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Modrinth file response", exception);
        }
    }

    private ClientInstallCategory category(JsonObject version) throws InterruptedException {
        try {
            String id = version.get("project_id").getAsString();
            if (!PROJECT_ID.matcher(id).matches()) return ClientInstallCategory.SERVER_REQUIRED;
            Optional<JsonObject> response = source.read(URI.create("https://api.modrinth.com/v2/project/" + id));
            if (response.isEmpty()) return ClientInstallCategory.SERVER_REQUIRED;
            JsonObject project = response.get();
            if (!id.equals(project.get("id").getAsString())) return ClientInstallCategory.SERVER_REQUIRED;
            return ClientInstallCategory.fromModrinthEvidence(true, project.get("client_side").getAsString());
        } catch (IOException | RuntimeException exception) {
            return ClientInstallCategory.SERVER_REQUIRED;
        }
    }

    void download(ResolutionPlan.Source match, ManifestEntry target, Path part,
                  BooleanSupplier cancelled, LongConsumer progress) throws IOException, InterruptedException {
        ModrinthDownload.download(match, target, part, cancelled, progress, uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new ModrinthDownload.Response(response.statusCode(), response.headers().allValues("Location"),
                    response.body());
        });
    }

    private Optional<JsonObject> fetch(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) throw new IOException("Modrinth lookup HTTP " + response.statusCode());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = body.read(buffer)) >= 0) {
                if (count > MAX_JSON_BYTES - bytes.size()) throw new IOException("Modrinth response too large");
                bytes.write(buffer, 0, count);
            }
            try {
                return Optional.of(JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (RuntimeException exception) {
                throw new IOException("Invalid Modrinth JSON", exception);
            }
        }
    }

    private static boolean contains(JsonArray array, String expected) {
        if (array == null) return false;
        for (JsonElement element : array) if (expected.equals(element.getAsString())) return true;
        return false;
    }
}
