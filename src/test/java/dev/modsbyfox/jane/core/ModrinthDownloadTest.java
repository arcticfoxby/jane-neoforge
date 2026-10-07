package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModrinthDownloadTest {
    private static final URI BASE = URI.create("https://cdn.modrinth.com/data/x/file.jar");
    private static final URI ALT = URI.create("https://cdn-alt.modrinth.com/other/file.jar");
    @TempDir Path dir;

    private static byte[] bytes() { return "exact trusted aircraft bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    private static ManifestEntry target(byte[] expected, long size) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(expected));
        return new ManifestEntry("immersive_aircraft", "Immersive Aircraft", "1.0", size, sha);
    }

    private static ResolutionPlan.Source source(ManifestEntry target) {
        return new ResolutionPlan.Source("immersive_aircraft.jar", BASE, target.fileSize());
    }

    private record Step(URI expected, int status, List<String> locations, byte[] body) {
        Step(URI expected, int status, String location, byte[] body) {
            this(expected, status, location == null ? List.of() : List.of(location), body);
        }
    }

    private static final class ClosingStream extends ByteArrayInputStream {
        boolean closed;
        ClosingStream(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    private static final class Script implements ModrinthDownload.Transport {
        private final List<Step> steps;
        private int calls;
        private ClosingStream previous;
        Script(Step... steps) { this.steps = List.of(steps); }
        @Override public ModrinthDownload.Response get(URI uri) {
            if (previous != null) assertTrue(previous.closed, "redirect body must close before next GET");
            assertTrue(calls < steps.size(), "unexpected extra request");
            Step step = steps.get(calls++);
            assertEquals(step.expected(), uri);
            previous = new ClosingStream(step.body());
            return new ModrinthDownload.Response(step.status(), step.locations(), previous);
        }
        void assertClosed() { assertTrue(previous.closed); }
    }

    @Test
    void direct200StreamsExactBody() throws Exception {
        byte[] bytes = bytes();
        ManifestEntry target = target(bytes, bytes.length);
        Path part = dir.resolve("direct.part");
        Script script = new Script(new Step(BASE, 200, List.of(), bytes));
        List<Long> progress = new ArrayList<>();
        ModrinthDownload.download(source(target), target, part, () -> false, progress::add, script);
        assertArrayEquals(bytes, Files.readAllBytes(part));
        StagedFileVerifier.verify(part, target);
        assertEquals((long) bytes.length, progress.get(progress.size() - 1));
        script.assertClosed();
    }

    @Test
    void supportsEachSpecifiedRedirectStatusAndClosesBodies() throws Exception {
        byte[] bytes = bytes();
        ManifestEntry target = target(bytes, bytes.length);
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            Path part = dir.resolve(status + ".part");
            Script script = new Script(new Step(BASE, status, ALT.toString(), new byte[]{1}),
                    new Step(ALT, 200, List.of(), bytes));
            ModrinthDownload.download(source(target), target, part, () -> false, count -> { }, script);
            assertEquals(2, script.calls);
            assertArrayEquals(bytes, Files.readAllBytes(part));
            script.assertClosed();
        }
    }

    @Test
    void resolvesRelativeLocationThenValidatesIt() throws Exception {
        byte[] bytes = bytes();
        ManifestEntry target = target(bytes, bytes.length);
        URI resolved = URI.create("https://cdn.modrinth.com/data/redirected/file.jar");
        Script script = new Script(new Step(BASE, 302, "../redirected/file.jar", new byte[0]),
                new Step(resolved, 200, List.of(), bytes));
        ModrinthDownload.download(source(target), target, dir.resolve("relative.part"), () -> false, count -> { }, script);
        assertEquals(2, script.calls);
        script.assertClosed();
    }

    @Test
    void acceptsOnlyCaseInsensitiveExactAllowlistedAlternateHost() throws Exception {
        byte[] bytes = bytes();
        ManifestEntry target = target(bytes, bytes.length);
        URI raw = URI.create("https://CDN-RAW.MODRINTH.COM/trusted.jar");
        Script script = new Script(new Step(BASE, 307, raw.toString(), new byte[0]),
                new Step(raw, 200, List.of(), bytes));
        ModrinthDownload.download(source(target), target, dir.resolve("raw.part"), () -> false, count -> { }, script);
        assertEquals(2, script.calls);
    }

    @Test
    void rejectsMissingMalformedAndUnsafeLocationsBeforeCreatingPart() throws Exception {
        ManifestEntry target = target(bytes(), bytes().length);
        List<List<String>> invalid = List.of(List.of(), List.of(" "), List.of("https://cdn.modrinth.com/x", "https://cdn-alt.modrinth.com/x"),
                List.of("::::"), List.of("http://cdn.modrinth.com/x"), List.of("https://evil.example/x"),
                List.of("https://cdn.modrinth.com.attacker.example/x"), List.of("https://user@cdn.modrinth.com/x"),
                List.of("https://cdn.modrinth.com:8443/x"), List.of("https://cdn.modrinth.com./x"),
                List.of("https://localhost/x"), List.of("https://127.0.0.1/x"), List.of("//evil.example/x"),
                List.of("https://cdn.modrinth.com/x#fragment"));
        for (int i = 0; i < invalid.size(); i++) {
            Path part = dir.resolve("invalid-" + i + ".part");
            Script script = new Script(new Step(BASE, 307, invalid.get(i), new byte[0]));
            assertThrows(IOException.class, () -> ModrinthDownload.download(source(target), target, part,
                    () -> false, count -> { }, script), invalid.get(i).toString());
            assertFalse(Files.exists(part));
            script.assertClosed();
        }
    }

    @Test
    void rejectsLoopsAndMoreThanThreeRedirects() throws Exception {
        ManifestEntry target = target(bytes(), bytes().length);
        Script loop = new Script(new Step(BASE, 307, ALT.toString(), new byte[0]),
                new Step(ALT, 307, BASE.toString(), new byte[0]));
        IOException loopError = assertThrows(IOException.class, () -> ModrinthDownload.download(source(target), target,
                dir.resolve("loop.part"), () -> false, count -> { }, loop));
        assertTrue(loopError.getMessage().contains("loop"));
        assertEquals(2, loop.calls);
        assertFalse(Files.exists(dir.resolve("loop.part")));

        URI third = URI.create("https://cdn-raw.modrinth.com/third.jar");
        URI fourth = URI.create("https://cdn.modrinth.com/fourth.jar");
        Script tooMany = new Script(new Step(BASE, 307, ALT.toString(), new byte[0]),
                new Step(ALT, 307, third.toString(), new byte[0]),
                new Step(third, 307, fourth.toString(), new byte[0]),
                new Step(fourth, 307, "https://cdn-alt.modrinth.com/fifth.jar", new byte[0]));
        IOException limit = assertThrows(IOException.class, () -> ModrinthDownload.download(source(target), target,
                dir.resolve("limit.part"), () -> false, count -> { }, tooMany));
        assertTrue(limit.getMessage().contains("Too many"));
        assertEquals(4, tooMany.calls);
        assertFalse(Files.exists(dir.resolve("limit.part")));
    }

    @Test
    void unsupportedRedirectsAndHttpFailuresNeverCreatePart() throws Exception {
        ManifestEntry target = target(bytes(), bytes().length);
        for (int status : new int[]{300, 304, 305, 306, 404, 500}) {
            assertFalse(ModrinthDownload.isRedirectStatus(status));
            Path part = dir.resolve("status-" + status + ".part");
            Script script = new Script(new Step(BASE, status, ALT.toString(), new byte[]{1}));
            assertThrows(IOException.class, () -> ModrinthDownload.download(source(target), target, part,
                    () -> false, count -> { }, script));
            assertFalse(Files.exists(part));
            script.assertClosed();
        }
    }

    @Test
    void transportFailureRemainsDownloadFailureWithoutPart() throws Exception {
        ManifestEntry target = target(bytes(), bytes().length);
        Path part = dir.resolve("network.part");
        assertThrows(IOException.class, () -> ModrinthDownload.download(source(target), target, part,
                () -> false, count -> { }, uri -> { throw new IOException("network unavailable"); }));
        assertFalse(Files.exists(part));
    }

    @Test
    void rejectsActualSizeAboveOrBelowTargetAndRemovesPart() throws Exception {
        byte[] expected = new byte[]{1, 2, 3};
        ManifestEntry target = target(expected, expected.length);
        for (byte[] body : new byte[][]{new byte[]{1, 2}, new byte[]{1, 2, 3, 4}}) {
            Path part = dir.resolve("size-" + body.length + ".part");
            Script script = new Script(new Step(BASE, 200, List.of(), body));
            assertThrows(IOException.class, () -> ModrinthDownload.download(source(target), target, part,
                    () -> false, count -> { }, script));
            assertFalse(Files.exists(part));
            script.assertClosed();
        }
    }

    @Test
    void cancellationAndBodyIoFailureRemovePart() throws Exception {
        byte[] bytes = bytes();
        ManifestEntry target = target(bytes, bytes.length);
        AtomicBoolean cancelled = new AtomicBoolean();
        Path cancelledPart = dir.resolve("cancelled.part");
        assertThrows(IOException.class, () -> ModrinthDownload.download(source(target), target, cancelledPart,
                cancelled::get, count -> cancelled.set(true), new Script(new Step(BASE, 200, List.of(), bytes))));
        assertFalse(Files.exists(cancelledPart));

        AtomicBoolean betweenHops = new AtomicBoolean();
        Path betweenPart = dir.resolve("between-hops.part");
        assertThrows(IOException.class, () -> ModrinthDownload.download(source(target), target, betweenPart,
                betweenHops::get, count -> { }, uri -> {
                    assertEquals(BASE, uri);
                    betweenHops.set(true);
                    return new ModrinthDownload.Response(307, List.of(ALT.toString()), new ByteArrayInputStream(new byte[0]));
                }));
        assertFalse(Files.exists(betweenPart));

        AtomicBoolean closed = new AtomicBoolean();
        InputStream broken = new InputStream() {
            private boolean first = true;
            @Override public int read() { return 1; }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (first) { first = false; buffer[offset] = 1; return 1; }
                throw new IOException("body interrupted");
            }
            @Override public void close() { closed.set(true); }
        };
        Path brokenPart = dir.resolve("broken.part");
        assertThrows(IOException.class, () -> ModrinthDownload.download(source(target), target, brokenPart,
                () -> false, count -> { }, uri -> new ModrinthDownload.Response(200, List.of(), broken)));
        assertFalse(Files.exists(brokenPart));
        assertTrue(closed.get());
    }

    @Test
    void aircraft307FixtureReachesVerifiedReadyAndUpdatePlan() throws Exception {
        byte[] bytes = bytes();
        ManifestEntry target = target(bytes, bytes.length);
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(target));
        var comparisons = Comparison.compare(manifest, Map.of(), path -> target.sha512());
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("example.org", manifest, comparisons));
        session.publishResolution(ResolutionPlan.resolve(comparisons, entry -> Optional.of(source(entry))));
        assertEquals(ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE, session.snapshot().items().get(0).item().classification());
        assertTrue(session.startDownloads());
        session.update(target.modId(), JaneSyncSession.RuntimeState.DOWNLOADING, 0);
        Path part = dir.resolve("immersive_aircraft.jar.part");
        Path staged = dir.resolve("immersive_aircraft.jar");
        Script script = new Script(new Step(BASE, 307, ALT.toString(), new byte[0]),
                new Step(ALT, 200, List.of(), bytes));
        ModrinthDownload.download(source(target), target, part, () -> false,
                count -> session.update(target.modId(), JaneSyncSession.RuntimeState.DOWNLOADING, count), script);
        StagedFileVerifier.verifyAndStage(part, staged, target, session, () -> false);
        session.finish(null);
        assertEquals(JaneSyncSession.RuntimeState.READY, session.snapshot().items().get(0).state());
        assertTrue(session.snapshot().canInstall());
        UpdatePlan plan = new UpdatePlan(UUID.randomUUID().toString(), session.context().serverId(),
                "2026-09-19_12-00-00", List.of(new UpdatePlan.Operation(target.modId(), UpdatePlan.Kind.ADD,
                null, null, source(target).name(), target.sha512(), target.fileSize())));
        assertEquals("immersive_aircraft.jar", plan.operations().get(0).newFile());
        assertFalse(Files.exists(part));
        assertTrue(Files.exists(staged));
    }
}
