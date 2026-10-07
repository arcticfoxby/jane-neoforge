package dev.modsbyfox.jane.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serves only the exact top-level JARs captured in the prepared required manifest. */
public final class ServerProviderService implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger("jane");
    public record File(ManifestEntry entry, Path path) {
        public File {
            java.util.Objects.requireNonNull(entry, "entry");
            java.util.Objects.requireNonNull(path, "path");
        }
    }

    private final Path gameDir;
    private final ServerProviderCore core;
    private final ServerSocket listener;
    private final ThreadPoolExecutor workers;
    private final Thread acceptThread;
    private final Set<Socket> active = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ServerProviderService(Path gameDir, Map<String, File> files, int bindPort, Clock clock) throws IOException {
        if (bindPort < 0 || bindPort > 65535) throw new IOException("Invalid provider bind port");
        this.gameDir = gameDir;
        this.core = new ServerProviderCore(gameDir, files.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, item -> new ServerProviderCore.File(item.getValue().entry(), item.getValue().path()))), clock);
        this.listener = new ServerSocket(bindPort, 16);
        this.workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "Jane ServerProvider worker");
            thread.setDaemon(true);
            return thread;
        }, (task, executor) -> { if (task instanceof Connection connection) connection.close(); });
        this.acceptThread = new Thread(this::accept, "Jane ServerProvider accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        LOGGER.log(System.Logger.Level.INFO, "Jane ServerProvider listening on port " + listener.getLocalPort());
    }

    public int port() { return listener.getLocalPort(); }

    public String issue(RequiredManifest manifest) {
        if (listener.isClosed()) throw new IllegalStateException("ServerProvider is stopped");
        return core.issue(manifest);
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                socket.setSoTimeout(30_000);
                active.add(socket);
                workers.execute(new Connection(socket));
            } catch (IOException exception) {
                if (!listener.isClosed()) LOGGER.log(System.Logger.Level.WARNING, "Jane ServerProvider accept failed", exception);
            }
        }
    }

    private final class Connection implements Runnable {
        private final Socket socket;
        private Connection(Socket socket) { this.socket = socket; }
        private void close() {
            active.remove(socket);
            try { socket.close(); } catch (IOException ignored) { }
        }
        @Override public void run() {
            try (socket; DataInputStream in = new DataInputStream(socket.getInputStream());
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                ServerProviderWire.Request request;
                try { request = ServerProviderWire.readRequest(in); }
                catch (IOException exception) { out.writeByte(ServerProviderWire.BAD_REQUEST); return; }
                ServerProviderCore.Access access = core.open(request.token(), request.sha512());
                if (access.status() != ServerProviderWire.OK) {
                    out.writeByte(access.status());
                    LOGGER.log(System.Logger.Level.WARNING, "Jane ServerProvider rejected request with status " + access.status());
                    return;
                }
                try (ServerProviderCore.Lease lease = access.lease()) {
                    out.writeByte(ServerProviderWire.OK);
                    out.writeLong(lease.entry().fileSize());
                    byte[] buffer = new byte[64 * 1024];
                    var stream = Channels.newInputStream(lease.channel());
                    int count;
                    while ((count = stream.read(buffer)) != -1) out.write(buffer, 0, count);
                    out.flush();
                    LOGGER.log(System.Logger.Level.INFO, "Jane ServerProvider served " + lease.entry().modId()
                            + " hash " + lease.entry().sha512().substring(0, 12));
                }
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Jane ServerProvider connection failed");
            } finally { close(); }
        }
    }

    @Override public void close() {
        try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : active) try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
        core.close();
        try { acceptThread.join(1000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
