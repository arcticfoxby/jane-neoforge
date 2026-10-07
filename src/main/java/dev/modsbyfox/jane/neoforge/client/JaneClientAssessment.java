package dev.modsbyfox.jane.neoforge.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.ServerIdentity;
import dev.modsbyfox.jane.neoforge.ClientManifestAssessment;
import dev.modsbyfox.jane.neoforge.NeoForgePhysicalDiscovery;
import dev.modsbyfox.jane.neoforge.network.ResultPayload;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client-only adapter for the configuration-phase physical JAR assessment. */
public final class JaneClientAssessment {
    private record AssessmentState(long generation, PendingSyncContext pending) { }

    private static final AtomicReference<AssessmentState> STATE =
            new AtomicReference<>(new AssessmentState(0, null));

    private JaneClientAssessment() { }

    public static CompletionStage<ResultPayload.Status> evaluate(ManifestCodec.LoginOffer offer, IPayloadContext context)
            throws IOException {
        long request = STATE.updateAndGet(state -> new AssessmentState(state.generation() + 1, null))
                .generation();
        var gameDir = FMLPaths.GAMEDIR.get();
        var jars = NeoForgePhysicalDiscovery.discover(gameDir);
        ServerData current = Minecraft.getInstance().getCurrentServer();
        String currentIp = current == null ? null : current.ip;
        SocketAddress remote = context.connection().getRemoteAddress();
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Comparison.Result> results = ClientManifestAssessment.assess(gameDir, offer.manifest(), jars);
                if (Comparison.passed(results)) return ResultPayload.Status.PASS;
                String address = serverAddress(currentIp, remote);
                if (context.connection().isConnected()) {
                    PendingSyncContext pending = new PendingSyncContext(address, offer.manifest(), results, offer.provider());
                    STATE.updateAndGet(state -> state.generation() == request
                            ? new AssessmentState(request, pending) : state);
                }
                return ResultPayload.Status.ACTION_REQUIRED;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    /** A later UI phase can consume this context after the connection has closed. */
    public static PendingSyncContext takePending() {
        return STATE.getAndUpdate(state -> new AssessmentState(state.generation(), null)).pending();
    }

    private static String serverAddress(String currentIp, SocketAddress remote) throws IOException {
        if (currentIp != null) {
            try {
                return ServerIdentity.normalize(currentIp);
            } catch (IllegalArgumentException ignored) {
                // A resolved socket address still gives a safe reconnect target.
            }
        }
        if (remote instanceof InetSocketAddress socket) {
            String host = socket.getHostString();
            if (host.contains(":")) host = "[" + host + "]";
            try {
                return ServerIdentity.normalize(host + ":" + socket.getPort());
            } catch (IllegalArgumentException exception) {
                throw new IOException("Cannot identify the Jane server", exception);
            }
        }
        throw new IOException("Cannot identify the Jane server during configuration");
    }
}
