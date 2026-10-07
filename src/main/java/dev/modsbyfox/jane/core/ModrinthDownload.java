package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Streams an exact Modrinth file through individually validated CDN redirects. */
public final class ModrinthDownload {
    public static final int MAX_DOWNLOAD_REDIRECTS = 3;
    private static final Set<String> REDIRECT_HOSTS = Set.of(
            "cdn.modrinth.com", "cdn-alt.modrinth.com", "cdn-raw.modrinth.com");
    private static final System.Logger LOGGER = System.getLogger("jane");

    public record Response(int status, List<String> locations, InputStream body) { }
    @FunctionalInterface public interface Transport {
        Response get(URI uri) throws IOException, InterruptedException;
    }

    private ModrinthDownload() { }

    public static void download(ResolutionPlan.Source source, ManifestEntry target, Path destination,
                                BooleanSupplier cancelled, LongConsumer progress, Transport transport)
            throws IOException, InterruptedException {
        if (source.size() != target.fileSize()) throw new IOException("Modrinth source size differs from required file");
        URI current = source.uri();
        Set<URI> visited = new HashSet<>();
        visited.add(current.normalize());
        int redirects = 0;
        while (true) {
            if (cancelled.getAsBoolean()) throw new IOException("Download cancelled");
            Response response = transport.get(current);
            if (response == null || response.body() == null) throw new IOException("Modrinth download returned no body");
            try (InputStream body = response.body()) {
                if (response.status() == 200) {
                    if (cancelled.getAsBoolean()) throw new IOException("Download cancelled");
                    copyExact(body, target, destination, cancelled, progress);
                    return;
                }
                if (!isRedirectStatus(response.status())) {
                    throw new IOException("Modrinth download returned HTTP " + response.status());
                }
                if (redirects >= MAX_DOWNLOAD_REDIRECTS) throw new IOException("Too many Modrinth download redirects");
                URI next = resolveRedirect(current, response.locations());
                if (!visited.add(next.normalize())) throw new IOException("Modrinth download redirect loop");
                LOGGER.log(System.Logger.Level.INFO, "Jane Modrinth redirect: " + current.getHost() + " -> " + next.getHost());
                current = next;
                redirects++;
            }
        }
    }

    public static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    static URI resolveRedirect(URI current, List<String> locations) throws IOException {
        if (locations == null || locations.size() != 1 || locations.get(0) == null || locations.get(0).isBlank()) {
            throw new IOException("Modrinth download redirect has no unique Location");
        }
        try {
            URI next = current.resolve(new URI(locations.get(0)));
            validateRedirectUri(next);
            return next;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IOException("Invalid Modrinth download redirect Location", exception);
        }
    }

    static void validateRedirectUri(URI uri) throws IOException {
        String host = uri.getHost();
        if (!uri.isAbsolute() || uri.isOpaque() || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                || host == null || !REDIRECT_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT))) {
            throw new IOException("Rejected Modrinth download redirect to "
                    + (host == null ? "invalid host" : host));
        }
    }

    private static void copyExact(InputStream body, ManifestEntry target, Path destination,
                                  BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        boolean created = false;
        try {
            try (OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                byte[] buffer = new byte[64 * 1024];
                long received = 0;
                int count;
                while ((count = body.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean()) throw new IOException("Download cancelled");
                    received += count;
                    if (received > target.fileSize()) throw new IOException("Download exceeds declared size");
                    out.write(buffer, 0, count);
                    progress.accept(received);
                }
                if (cancelled.getAsBoolean()) throw new IOException("Download cancelled");
                if (received != target.fileSize()) throw new IOException("Download size mismatch");
            }
        } catch (IOException | RuntimeException exception) {
            if (created) {
                try { Files.deleteIfExists(destination); }
                catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            }
            throw exception;
        }
    }
}
