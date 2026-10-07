package dev.modsbyfox.jane.neoforge.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.RequiredManifest;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.Test;

class JaneConfigurationNetworkTest {
    private static final ManifestCodec.LoginOffer OFFER =
            new ManifestCodec.LoginOffer(new RequiredManifest(RequiredManifest.PROTOCOL, List.of()), null);

    @Test
    void manifestPayloadFramesProtocolThreeBytesAndRejectsOversize() throws IOException {
        ManifestPayload original = new ManifestPayload(ManifestCodec.encode(OFFER));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ManifestPayload.STREAM_CODEC.encode(buffer, original);
        assertEquals(OFFER, ManifestCodec.decodeOffer(ManifestPayload.STREAM_CODEC.decode(buffer).bytes()));

        FriendlyByteBuf oversized = new FriendlyByteBuf(Unpooled.buffer());
        oversized.writeVarInt(RequiredManifest.MAX_PAYLOAD + 1);
        assertThrows(IllegalArgumentException.class, () -> ManifestPayload.STREAM_CODEC.decode(oversized));
    }

    @Test
    void asynchronousPassAcknowledgesOnlyAfterBothStagesComplete() {
        CompletableFuture<ManifestCodec.LoginOffer> prepared = new CompletableFuture<>();
        CompletableFuture<ResultPayload.Status> evaluated = new CompletableFuture<>();
        JaneConfigurationNetwork network = network(() -> prepared, (offer, context) -> evaluated, Duration.ofSeconds(2));

        CallLog server = new CallLog(true);
        AtomicReference<CustomPacketPayload> sent = new AtomicReference<>();
        network.new ManifestTask(server.serverListener()).run(sent::set);
        assertNull(sent.get());
        prepared.complete(OFFER);
        assertTrue(sent.get() instanceof ManifestPayload);

        CallLog client = new CallLog(false);
        network.handleManifest((ManifestPayload) sent.get(), client.context());
        assertNull(client.reply);
        evaluated.complete(ResultPayload.Status.PASS);
        assertEquals(ResultPayload.Status.PASS, client.reply.status());
        assertFalse(client.disconnected);

        network.handleResult(client.reply, server.context());
        assertTrue(server.finished);
        assertFalse(server.disconnected);
    }

    @Test
    void requiredActionDisconnectsWithoutFinishingTask() {
        JaneConfigurationNetwork network = network(() -> CompletableFuture.completedFuture(OFFER),
                (offer, context) -> CompletableFuture.completedFuture(ResultPayload.Status.ACTION_REQUIRED),
                Duration.ofSeconds(2));
        CallLog server = new CallLog(true);
        network.new ManifestTask(server.serverListener()).run(payload -> {});

        CallLog client = new CallLog(false);
        network.handleManifest(payload(), client.context());
        assertEquals(ResultPayload.Status.ACTION_REQUIRED, client.reply.status());
        assertTrue(client.disconnected);

        network.handleResult(client.reply, server.context());
        assertTrue(server.disconnected);
        assertFalse(server.finished);
    }

    @Test
    void badManifestAndSynchronousEvaluatorFailureFailClosed() {
        CallLog invalid = new CallLog(false);
        network(() -> CompletableFuture.completedFuture(OFFER),
                (offer, context) -> CompletableFuture.completedFuture(ResultPayload.Status.PASS), Duration.ofSeconds(2))
                .handleManifest(new ManifestPayload(new byte[] { 1, 2, 3 }), invalid.context());
        assertEquals(ResultPayload.Status.PROTOCOL_ERROR, invalid.reply.status());
        assertTrue(invalid.disconnected);

        CallLog failed = new CallLog(false);
        network(() -> CompletableFuture.completedFuture(OFFER), (offer, context) -> {
            throw new IOException("Local comparison failed");
        }, Duration.ofSeconds(2)).handleManifest(payload(), failed.context());
        assertEquals(ResultPayload.Status.PROTOCOL_ERROR, failed.reply.status());
        assertTrue(failed.disconnected);
    }

    @Test
    void exceptionalClientStageDisconnectsAndDoesNotPass() {
        CallLog client = new CallLog(false);
        network(() -> CompletableFuture.completedFuture(OFFER),
                (offer, context) -> CompletableFuture.failedFuture(new IOException("hash failed")), Duration.ofSeconds(2))
                .handleManifest(payload(), client.context());
        assertEquals(ResultPayload.Status.PROTOCOL_ERROR, client.reply.status());
        assertTrue(client.disconnected);
        assertFalse(client.finished);
    }

    @Test
    void clientTimeoutDisconnectsAndLateCompletionCannotReplyAgain() throws InterruptedException {
        CompletableFuture<ResultPayload.Status> pending = new CompletableFuture<>();
        JaneConfigurationNetwork network = network(() -> CompletableFuture.completedFuture(OFFER),
                (offer, context) -> pending, Duration.ofMillis(20));
        CallLog client = new CallLog(false);
        network.handleManifest(payload(), client.context());
        assertTrue(client.disconnectedSignal.await(2, TimeUnit.SECONDS));
        assertEquals(ResultPayload.Status.PROTOCOL_ERROR, client.reply.status());

        ResultPayload reply = client.reply;
        pending.complete(ResultPayload.Status.PASS);
        assertSame(reply, client.reply);
        assertFalse(client.finished);
    }

    @Test
    void serverTimeoutDisconnectsAndLateCompletionCannotSend() throws InterruptedException {
        CompletableFuture<ManifestCodec.LoginOffer> pending = new CompletableFuture<>();
        JaneConfigurationNetwork network = network(() -> pending,
                (offer, context) -> CompletableFuture.completedFuture(ResultPayload.Status.PASS), Duration.ofMillis(20));
        CallLog server = new CallLog(true);
        AtomicReference<CustomPacketPayload> sent = new AtomicReference<>();
        network.new ManifestTask(server.serverListener()).run(sent::set);
        assertTrue(server.disconnectedSignal.await(2, TimeUnit.SECONDS));
        pending.complete(OFFER);
        assertNull(sent.get());
        assertFalse(server.finished);
    }

    @Test
    void missingResultTimesOutAndLatePassCannotFinishTask() throws InterruptedException {
        JaneConfigurationNetwork network = network(() -> CompletableFuture.completedFuture(OFFER),
                (offer, context) -> CompletableFuture.completedFuture(ResultPayload.Status.PASS),
                Duration.ofMillis(20));
        CallLog server = new CallLog(true);
        AtomicReference<CustomPacketPayload> sent = new AtomicReference<>();
        network.new ManifestTask(server.serverListener()).run(sent::set);
        assertTrue(sent.get() instanceof ManifestPayload);
        assertTrue(server.disconnectedSignal.await(2, TimeUnit.SECONDS));
        network.handleResult(new ResultPayload(ResultPayload.Status.PASS), server.context());
        assertFalse(server.finished);
    }

    @Test
    void completedHandshakeCancelsResultTimeout() throws InterruptedException {
        JaneConfigurationNetwork network = network(() -> CompletableFuture.completedFuture(OFFER),
                (offer, context) -> CompletableFuture.completedFuture(ResultPayload.Status.PASS),
                Duration.ofMillis(100));
        CallLog server = new CallLog(true);
        network.new ManifestTask(server.serverListener()).run(payload -> {});
        network.handleResult(new ResultPayload(ResultPayload.Status.PASS), server.context());
        assertTrue(server.finished);
        assertFalse(server.disconnectedSignal.await(250, TimeUnit.MILLISECONDS));
    }

    @Test
    void earlyResultDisconnectsAndPreventsLaterManifest() {
        CompletableFuture<ManifestCodec.LoginOffer> pending = new CompletableFuture<>();
        JaneConfigurationNetwork network = network(() -> pending,
                (offer, context) -> CompletableFuture.completedFuture(ResultPayload.Status.PASS), Duration.ofSeconds(2));
        CallLog server = new CallLog(true);
        AtomicReference<CustomPacketPayload> sent = new AtomicReference<>();
        network.new ManifestTask(server.serverListener()).run(sent::set);
        network.handleResult(new ResultPayload(ResultPayload.Status.PASS), server.context());
        assertTrue(server.disconnected);
        assertFalse(server.finished);
        pending.complete(OFFER);
        assertNull(sent.get());
    }

    private static JaneConfigurationNetwork network(JaneConfigurationNetwork.ManifestSupplier supplier,
            JaneConfigurationNetwork.ClientEvaluator evaluator, Duration timeout) {
        return new JaneConfigurationNetwork(supplier, evaluator, timeout, (listener, task) -> task.run());
    }

    private static ManifestPayload payload() {
        try {
            return new ManifestPayload(ManifestCodec.encode(OFFER));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class TestConnection extends Connection {
        private volatile PacketListener currentListener;
        private volatile boolean connected = true;
        private Runnable onDisconnect;

        private TestConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public PacketListener getPacketListener() {
            return currentListener;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void disconnect(Component reason) {
            connected = false;
            if (onDisconnect != null) onDisconnect.run();
        }
    }

    private static final class CallLog {
        private final TestConnection connection = new TestConnection();
        private final ICommonPacketListener listener;
        private final CountDownLatch disconnectedSignal = new CountDownLatch(1);
        private volatile ResultPayload reply;
        private volatile boolean disconnected;
        private volatile boolean finished;

        private CallLog(boolean server) {
            Class<?> listenerType = server ? ServerConfigurationPacketListener.class : ICommonPacketListener.class;
            listener = (ICommonPacketListener) Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[] { listenerType }, (proxy, method, args) -> switch (method.getName()) {
                        case "getConnection" -> connection;
                        case "isAcceptingMessages" -> connection.connected;
                        case "disconnect" -> {
                            assertTrue(args[0] instanceof Component);
                            disconnect();
                            yield null;
                        }
                        default -> throw new AssertionError("Unexpected listener call: " + method.getName());
                    });
            connection.currentListener = listener;
            connection.onDisconnect = this::disconnect;
        }

        private ServerConfigurationPacketListener serverListener() {
            return (ServerConfigurationPacketListener) listener;
        }

        private void disconnect() {
            disconnected = true;
            connection.connected = false;
            disconnectedSignal.countDown();
        }

        private IPayloadContext context() {
            return (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                    new Class<?>[] { IPayloadContext.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "listener" -> listener;
                        case "enqueueWork" -> {
                            ((Runnable) args[0]).run();
                            yield CompletableFuture.completedFuture(null);
                        }
                        case "reply" -> {
                            reply = (ResultPayload) args[0];
                            yield null;
                        }
                        case "disconnect" -> {
                            assertTrue(args[0] instanceof Component);
                            disconnect();
                            yield null;
                        }
                        case "finishCurrentTask" -> {
                            finished = true;
                            yield null;
                        }
                        default -> throw new AssertionError("Unexpected context call: " + method.getName());
                    });
        }
    }
}
