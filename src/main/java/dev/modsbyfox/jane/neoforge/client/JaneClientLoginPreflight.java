package dev.modsbyfox.jane.neoforge.client;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.neoforge.network.JaneLoginAnswerPayload;
import dev.modsbyfox.jane.neoforge.network.JaneLoginQueryPayload;
import dev.modsbyfox.jane.neoforge.network.ResultPayload;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;

/** Handles one Jane query on the original, authenticated login connection. */
public final class JaneClientLoginPreflight {
    private static final long TIMEOUT_MINUTES = 3;
    private static final Component ACTION_REQUIRED = Component.literal(
            "Jane requires mod changes before joining. Apply them, restart Minecraft, and reconnect.");
    private static final Component PROTOCOL_ERROR = Component.literal(
            "Jane protocol mismatch or invalid login manifest.");

    private final JaneLoginPreflightGate gate = new JaneLoginPreflightGate();

    public void handle(Connection connection, ClientHandshakePacketListenerImpl listener,
            ClientboundCustomQueryPacket packet) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(packet, "packet");
        if (!gate.begin()) {
            fail(connection, listener);
            return;
        }
        if (packet.transactionId() != JaneLoginQueryPayload.TRANSACTION_ID
                || !(packet.payload() instanceof JaneLoginQueryPayload payload)
                || !isCurrent(connection, listener)) {
            fail(connection, listener);
            return;
        }
        CompletionStage<ResultPayload.Status> stage;
        try {
            stage = JaneClientLifecycle.evaluateLogin(ManifestCodec.decodeOffer(payload.bytes()), connection);
        } catch (IOException | RuntimeException exception) {
            fail(connection, listener);
            return;
        }
        CompletableFuture<ResultPayload.Status> bounded = new CompletableFuture<>();
        try {
            stage.whenComplete((status, error) -> {
                if (error == null) bounded.complete(status);
                else bounded.completeExceptionally(error);
            });
        } catch (RuntimeException exception) {
            bounded.completeExceptionally(exception);
        }
        bounded.orTimeout(TIMEOUT_MINUTES, TimeUnit.MINUTES).whenComplete((status, error) -> {
            if (!gate.finish() || !isCurrent(connection, listener)) return;
            ResultPayload.Status answer = error == null && status != null
                    ? status : ResultPayload.Status.PROTOCOL_ERROR;
            reply(connection, answer);
        });
    }

    private void fail(Connection connection, ClientHandshakePacketListenerImpl listener) {
        if (gate.fail() && isCurrent(connection, listener))
            reply(connection, ResultPayload.Status.PROTOCOL_ERROR);
    }

    private static boolean isCurrent(Connection connection, ClientHandshakePacketListenerImpl listener) {
        try {
            return connection.isConnected() && connection.getPacketListener() == listener;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void reply(Connection connection, ResultPayload.Status status) {
        if (status == ResultPayload.Status.PROTOCOL_ERROR) JaneClientLifecycle.abortLogin(connection);
        try {
            connection.send(new ServerboundCustomQueryAnswerPacket(JaneLoginQueryPayload.TRANSACTION_ID,
                    new JaneLoginAnswerPayload(status)));
        } catch (RuntimeException exception) {
            connection.disconnect(PROTOCOL_ERROR);
            return;
        }
        if (status == ResultPayload.Status.ACTION_REQUIRED) connection.disconnect(ACTION_REQUIRED);
        else if (status == ResultPayload.Status.PROTOCOL_ERROR) connection.disconnect(PROTOCOL_ERROR);
    }
}
