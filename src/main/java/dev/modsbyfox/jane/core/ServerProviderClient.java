package dev.modsbyfox.jane.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Downloads one exact manifest file to a temporary staging file. */
public final class ServerProviderClient {
    private ServerProviderClient() { }

    public static void download(String capturedAddress, ServerProviderOffer offer, ManifestEntry entry, Path part,
                                BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        download(capturedAddress, offer, entry, part, cancelled, progress, 30_000);
    }

    static void download(String capturedAddress, ServerProviderOffer offer, ManifestEntry entry, Path part,
                         BooleanSupplier cancelled, LongConsumer progress, int readTimeoutMillis) throws IOException {
        if (Files.exists(part)) throw new IOException("Provider temporary file already exists");
        Socket socket = new Socket();
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Thread cancelMonitor = new Thread(() -> {
            while (!finished.get()) {
                if (cancelled.getAsBoolean()) {
                    try { socket.close(); } catch (IOException ignored) { }
                    return;
                }
                try { Thread.sleep(100); }
                catch (InterruptedException exception) { return; }
            }
        }, "Jane ServerProvider cancel");
        cancelMonitor.setDaemon(true);
        cancelMonitor.start();
        try (socket) {
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            if (offer.transport() != ServerProviderTransport.SEPARATE_PORT)
                throw new IOException("Incorrect Provider transport");
            socket.connect(ServerIdentity.providerEndpoint(capturedAddress, offer.advertisedPort().orElseThrow()), 12_000);
            socket.setSoTimeout(readTimeoutMillis);
            transfer(socket.getInputStream(), socket.getOutputStream(), offer, entry, part, cancelled, progress);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(part);
            throw exception;
        } finally {
            finished.set(true);
            cancelMonitor.interrupt();
        }
    }

    /** Shared bounded response reader; the transport establishes the socket before calling this. */
    public static void transfer(InputStream input, OutputStream output, ServerProviderOffer offer, ManifestEntry entry,
                                Path part, BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        DataInputStream in = new DataInputStream(input);
        try {
            ServerProviderWire.writeRequest(out, offer.token(), entry.sha512());
            int status = in.readUnsignedByte();
            if (status != ServerProviderWire.OK) throw new IOException("ServerProvider " + switch (status) {
                case ServerProviderWire.UNAUTHORIZED -> "unauthorized";
                case ServerProviderWire.NOT_ALLOWED -> "not allowed";
                case ServerProviderWire.STALE -> "stale file";
                case ServerProviderWire.BAD_REQUEST -> "bad request";
                case ServerProviderWire.SERVER_ERROR -> "server error";
                default -> "unknown status";
            });
            long size = in.readLong();
            if (size != entry.fileSize()) throw new IOException("ServerProvider size differs from manifest");
            long received = 0;
            try (var file = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                while (received < size) {
                    if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
                    int count = in.read(buffer, 0, (int) Math.min(buffer.length, size - received));
                    if (count < 0) throw new IOException("ServerProvider response ended early");
                    file.write(buffer, 0, count);
                    received += count;
                    progress.accept(received);
                }
            }
            if (in.read() != -1) throw new IOException("ServerProvider response exceeded manifest size");
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(part);
            throw exception;
        }
    }
}
