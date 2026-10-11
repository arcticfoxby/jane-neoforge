package dev.modsbyfox.jane.neoforge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.ManifestEntry;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModrinthClientTest {
    private static final String HASH = "a".repeat(128);
    private static final ManifestEntry TARGET = new ManifestEntry("example", "Example", "1", 4, HASH);

    @Test void exactNeoForgeFileCanAdviseOptionalOnlyAfterProjectIdentityMatches() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModrinthClient client = new ModrinthClient(uri -> {
            calls.incrementAndGet();
            if (uri.getPath().startsWith("/v2/version_file/")) return Optional.of(version("neoforge", "1.21.1",
                    "https://cdn.modrinth.com/data/P/versions/v/example.jar", HASH));
            return Optional.of(json("{\"id\":\"ABC123\",\"client_side\":\"optional\"}"));
        });

        ModrinthClient.Match match = client.find(TARGET);

        assertEquals(2, calls.get());
        assertTrue(match.source().isPresent());
        assertEquals(URI.create("https://cdn.modrinth.com/data/P/versions/v/example.jar"),
                match.source().orElseThrow().uri());
        assertEquals(ClientInstallCategory.CLIENT_OPTIONAL, match.category());
    }

    @Test void incompatibleLoaderOrGameVersionMeansNoTrustedSourceButNotLookupFailure() throws Exception {
        for (String[] pair : new String[][] {{"fabric", "1.21.1"}, {"neoforge", "1.20.1"}}) {
            AtomicInteger calls = new AtomicInteger();
            ModrinthClient client = new ModrinthClient(uri -> {
                calls.incrementAndGet();
                return Optional.of(version(pair[0], pair[1],
                        "https://cdn.modrinth.com/data/P/versions/v/example.jar", HASH));
            });
            ModrinthClient.Match match = client.find(TARGET);
            assertTrue(match.source().isEmpty());
            assertEquals(ClientInstallCategory.SERVER_REQUIRED, match.category());
            assertEquals(1, calls.get());
        }
    }

    @Test void contradictoryExactHashOrUntrustedDownloadHostFailsClosed() {
        assertThrows(IOException.class, () -> new ModrinthClient(uri -> Optional.of(version(
                "neoforge", "1.21.1", "https://cdn.modrinth.com/data/P/versions/v/example.jar",
                "b".repeat(128)))).find(TARGET));
        assertThrows(IOException.class, () -> new ModrinthClient(uri -> Optional.of(version(
                "neoforge", "1.21.1", "https://example.com/example.jar", HASH))).find(TARGET));
    }

    @Test void missingExactVersionIsNotATrustedSource() throws Exception {
        ModrinthClient.Match match = new ModrinthClient(uri -> Optional.empty()).find(TARGET);
        assertFalse(match.source().isPresent());
    }

    private static JsonObject version(String loader, String game, String url, String hash) {
        return json("{\"project_id\":\"ABC123\",\"loaders\":[\"" + loader + "\"],"
                + "\"game_versions\":[\"" + game + "\"],\"files\":[{\"hashes\":{\"sha512\":\""
                + hash + "\"},\"size\":4,\"filename\":\"example.jar\",\"url\":\"" + url + "\"}]}");
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
