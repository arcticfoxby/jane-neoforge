package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerProviderTransferTest {
    @TempDir Path game;

    private byte[] request(int port, String token, String hash) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            ServerProviderWire.writeRequest(new DataOutputStream(socket.getOutputStream()), token, hash);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int status = in.readUnsignedByte();
            if (status != ServerProviderWire.OK) return new byte[]{(byte) status};
            long size = in.readLong();
            return in.readNBytes(Math.toIntExact(size));
        }
    }

    @Test void enabledProviderDoesNotSilentlyIgnoreBindFailure() throws Exception {
        try (java.net.ServerSocket occupied = new java.net.ServerSocket(0)) {
            assertThrows(IOException.class, () -> new ServerProviderService(game, Map.of(),
                    occupied.getLocalPort(), Clock.systemUTC()));
        }
    }

    @Test void exactFileIsServedButOtherHashesAndStaleFilesAreRejected() throws Exception {
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path jar = mods.resolve("fixture.jar");
        byte[] bytes = "small fixture JAR bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(jar, bytes);
        Path excluded = mods.resolve("excluded.jar");
        Files.writeString(excluded, "server-only content");
        String excludedHash = Hashing.sha512(excluded);
        String hash = Hashing.sha512(jar);
        ManifestEntry entry = new ManifestEntry("fixture", "Fixture", "1", bytes.length, hash);
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(entry));
        try (ServerProviderService service = new ServerProviderService(game,
                Map.of(hash, new ServerProviderService.File(entry, jar)), 0, Clock.systemUTC())) {
            String token = service.issue(manifest);
            assertArrayEquals(bytes, request(service.port(), token, hash));
            assertArrayEquals(new byte[]{ServerProviderWire.UNAUTHORIZED}, request(service.port(), "0".repeat(64), hash));
            assertArrayEquals(new byte[]{ServerProviderWire.NOT_ALLOWED}, request(service.port(), token, "b".repeat(128)));
            assertArrayEquals(new byte[]{ServerProviderWire.NOT_ALLOWED}, request(service.port(), token, excludedHash));
            Path part = game.resolve("download.jar.part");
            ServerProviderClient.download("127.0.0.1:25565", new ServerProviderOffer(service.port(), token),
                    entry, part, () -> false, received -> { });
            assertEquals(hash, Hashing.sha512(part));
            Files.writeString(jar, "changed");
            assertArrayEquals(new byte[]{ServerProviderWire.STALE}, request(service.port(), token, hash));
            Files.writeString(jar, "changed to a larger invalid fixture");
            assertArrayEquals(new byte[]{ServerProviderWire.STALE}, request(service.port(), token, hash));
            Files.delete(jar);
            assertArrayEquals(new byte[]{ServerProviderWire.STALE}, request(service.port(), token, hash));
        }
    }

    @Test void symlinkEscapeIsRejectedWhenSupported() throws Exception {
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path jar = mods.resolve("fixture.jar");
        Files.writeString(jar, "fixture");
        ManifestEntry entry = new ManifestEntry("fixture", "Fixture", "1", Files.size(jar), Hashing.sha512(jar));
        try (ServerProviderService service = new ServerProviderService(game,
                Map.of(entry.sha512(), new ServerProviderService.File(entry, jar)), 0, Clock.systemUTC())) {
            String token = service.issue(new RequiredManifest(RequiredManifest.PROTOCOL, List.of(entry)));
            Files.delete(jar);
            Path outside = game.resolve("outside.jar");
            Files.writeString(outside, "fixture");
            try { Files.createSymbolicLink(jar, outside); }
            catch (IOException | UnsupportedOperationException exception) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unavailable without elevated privileges");
            }
            assertArrayEquals(new byte[]{ServerProviderWire.STALE}, request(service.port(), token, entry.sha512()));
        }
    }

    @Test void clientRejectsWrongHeaderShortLongAndFailureStatus() throws Exception {
        byte[] body = "good bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path expected = game.resolve("expected.jar");
        Files.write(expected, body);
        ManifestEntry entry = new ManifestEntry("fixture", "Fixture", "1", body.length, Hashing.sha512(expected));
        for (int scenario = 0; scenario < 5; scenario++) {
            final int caseNumber = scenario;
            try (java.net.ServerSocket fake = new java.net.ServerSocket(0)) {
                Thread server = new Thread(() -> {
                    try (Socket socket = fake.accept()) {
                        socket.setSoTimeout(3000);
                        ServerProviderWire.readRequest(new DataInputStream(socket.getInputStream()));
                        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        if (caseNumber == 3 || caseNumber == 4) out.writeByte(caseNumber == 3
                                ? ServerProviderWire.UNAUTHORIZED : ServerProviderWire.STALE);
                        else {
                            out.writeByte(ServerProviderWire.OK);
                            out.writeLong(caseNumber == 0 ? body.length + 1 : body.length);
                            if (caseNumber == 1) out.write(body, 0, 2);
                            else if (caseNumber == 2) { out.write(body); out.writeByte(1); }
                        }
                        out.flush();
                    } catch (IOException ignored) { }
                });
                server.setDaemon(true);
                server.start();
                Path part = game.resolve("case" + scenario + ".part");
                assertThrows(IOException.class, () -> ServerProviderClient.download("127.0.0.1",
                        new ServerProviderOffer(fake.getLocalPort(), "a".repeat(64)), entry, part, () -> false,
                        received -> { }));
                assertFalse(Files.exists(part));
                server.join(3000);
            }
        }
    }

    @Test void clientReadTimeoutLeavesNoPartFile() throws Exception {
        Path expected = game.resolve("expected.jar");
        Files.writeString(expected, "fixture");
        ManifestEntry entry = new ManifestEntry("fixture", "Fixture", "1", Files.size(expected), Hashing.sha512(expected));
        try (java.net.ServerSocket fake = new java.net.ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket socket = fake.accept()) {
                    ServerProviderWire.readRequest(new DataInputStream(socket.getInputStream()));
                    Thread.sleep(500);
                } catch (IOException | InterruptedException ignored) { }
            });
            server.setDaemon(true);
            server.start();
            Path part = game.resolve("timeout.jar.part");
            assertThrows(java.net.SocketTimeoutException.class, () -> ServerProviderClient.download("127.0.0.1",
                    new ServerProviderOffer(fake.getLocalPort(), "a".repeat(64)), entry, part, () -> false,
                    received -> { }, 100));
            assertFalse(Files.exists(part));
            server.join(1500);
        }
    }

    @Test void cancellationClosesSocketAndLeavesNoPartFile() throws Exception {
        Path expected = game.resolve("expected.jar");
        Files.writeString(expected, "fixture");
        ManifestEntry entry = new ManifestEntry("fixture", "Fixture", "1", Files.size(expected), Hashing.sha512(expected));
        try (java.net.ServerSocket fake = new java.net.ServerSocket(0)) {
            java.util.concurrent.CountDownLatch accepted = new java.util.concurrent.CountDownLatch(1);
            Thread server = new Thread(() -> {
                try (Socket socket = fake.accept()) {
                    ServerProviderWire.readRequest(new DataInputStream(socket.getInputStream()));
                    accepted.countDown();
                    socket.getInputStream().read();
                } catch (IOException ignored) { }
            });
            server.setDaemon(true);
            server.start();
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            Path part = game.resolve("cancel.jar.part");
            var task = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    ServerProviderClient.download("127.0.0.1", new ServerProviderOffer(fake.getLocalPort(),
                            "a".repeat(64)), entry, part, cancelled::get, count -> { });
                    fail("Cancelled transfer should fail");
                } catch (IOException expectedFailure) { }
            });
            assertTrue(accepted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            cancelled.set(true);
            task.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(Files.exists(part));
            server.join(3000);
        }
    }

    @Test void exactLengthWrongHashCannotBecomeReady() throws Exception {
        Path expected = game.resolve("expected.jar");
        Files.writeString(expected, "good");
        ManifestEntry entry = new ManifestEntry("fixture", "Fixture", "1", 4, Hashing.sha512(expected));
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(entry));
        var comparisons = Comparison.compare(manifest, Map.of(), path -> "");
        JaneSyncSession session = new JaneSyncSession(new PendingSyncContext("127.0.0.1", manifest, comparisons,
                new ServerProviderOffer(25566, "a".repeat(64))));
        session.publishResolution(ResolutionPlan.resolve(comparisons, target -> java.util.Optional.empty(), true, count -> { }));
        assertTrue(session.startServerOnlyConfirmed());
        try (java.net.ServerSocket fake = new java.net.ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket socket = fake.accept()) {
                    ServerProviderWire.readRequest(new DataInputStream(socket.getInputStream()));
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeByte(ServerProviderWire.OK);
                    out.writeLong(4);
                    out.write("evil".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) { }
            });
            server.setDaemon(true);
            server.start();
            Path part = game.resolve("wrong.jar.part");
            ServerProviderClient.download("127.0.0.1", new ServerProviderOffer(fake.getLocalPort(), "a".repeat(64)),
                    entry, part, () -> false, count -> session.update(entry.modId(), JaneSyncSession.RuntimeState.DOWNLOADING, count));
            assertThrows(IOException.class, () -> StagedFileVerifier.verifyAndStage(part, game.resolve("wrong.jar"),
                    entry, session, () -> false));
            assertEquals(JaneSyncSession.RuntimeState.FAILED, session.snapshot().items().get(0).state());
            assertFalse(Files.exists(game.resolve("wrong.jar")));
            server.join(3000);
        }
    }

    @Test void largeFileStreamsThroughSharedProviderCore() throws Exception {
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path jar = mods.resolve("large-fixture.jar");
        byte[] block = new byte[64 * 1024];
        new java.util.Random(42).nextBytes(block);
        try (var output = Files.newOutputStream(jar)) {
            for (int index = 0; index < 256; index++) output.write(block);
        }
        ManifestEntry entry = new ManifestEntry("large_fixture", "Large Fixture", "1", Files.size(jar), Hashing.sha512(jar));
        try (ServerProviderService service = new ServerProviderService(game,
                Map.of(entry.sha512(), new ServerProviderService.File(entry, jar)), 0, Clock.systemUTC())) {
            String token = service.issue(new RequiredManifest(RequiredManifest.PROTOCOL, List.of(entry)));
            Path part = game.resolve("large-fixture.jar.part");
            java.util.concurrent.atomic.AtomicLong progress = new java.util.concurrent.atomic.AtomicLong();
            ServerProviderClient.download("127.0.0.1", new ServerProviderOffer(service.port(), token), entry,
                    part, () -> false, progress::set);
            assertEquals(entry.fileSize(), progress.get());
            assertEquals(entry.fileSize(), Files.size(part));
            assertEquals(entry.sha512(), Hashing.sha512(part));
        }
    }
}
