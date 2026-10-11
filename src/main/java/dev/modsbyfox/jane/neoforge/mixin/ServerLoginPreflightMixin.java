package dev.modsbyfox.jane.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import dev.modsbyfox.jane.neoforge.network.JaneLoginPreflight;
import dev.modsbyfox.jane.neoforge.network.JaneLoginQueryPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Send the Jane manifest after admission checks, before vanilla enters configuration. */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPreflightMixin {
    private static final Component PROTOCOL_ERROR = Component.literal("Jane login preflight failed or timed out.");

    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private Connection connection;
    @Shadow public abstract void disconnect(Component reason);
    @Invoker("finishLoginAndWaitForClient")
    protected abstract void jane$finishLogin(GameProfile profile);

    @Unique private JaneLoginPreflight.Session jane$session;
    @Unique private boolean jane$resuming;

    @Inject(method = "finishLoginAndWaitForClient", at = @At("HEAD"), cancellable = true)
    private void jane$holdLogin(GameProfile profile, CallbackInfo callback) {
        // Integrated worlds use a local Netty channel and have no remote server to preflight.
        if (jane$resuming || connection.isMemoryConnection()) return;
        callback.cancel();
        if (jane$session != null) {
            disconnect(PROTOCOL_ERROR);
            return;
        }
        try {
            ServerLoginPacketListenerImpl self = (ServerLoginPacketListenerImpl) (Object) this;
            JaneLoginPreflight.Session session = JaneLoginPreflight.newSession(server::execute,
                    () -> connection.isConnected() && connection.getPacketListener() == self,
                    payload -> connection.send(new ClientboundCustomQueryPacket(
                            JaneLoginQueryPayload.TRANSACTION_ID, payload)),
                    () -> {
                        jane$resuming = true;
                        try {
                            jane$finishLogin(profile);
                        } finally {
                            jane$resuming = false;
                        }
                    }, this::disconnect, connection::disconnect);
            jane$session = session;
            session.begin();
        } catch (RuntimeException exception) {
            disconnect(PROTOCOL_ERROR);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void jane$pauseVanillaTimeout(CallbackInfo callback) {
        JaneLoginPreflight.Session session = jane$session;
        if (session != null && session.tickAndHold()) callback.cancel();
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void jane$handleAnswer(ServerboundCustomQueryAnswerPacket packet, CallbackInfo callback) {
        if (packet.transactionId() != JaneLoginQueryPayload.TRANSACTION_ID) return;
        PacketUtils.ensureRunningOnSameThread(packet, (ServerLoginPacketListenerImpl) (Object) this, server);
        callback.cancel();
        JaneLoginPreflight.Session session = jane$session;
        if (session == null) {
            disconnect(PROTOCOL_ERROR);
            return;
        }
        session.accept(packet.transactionId(), packet.payload());
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void jane$releaseOnDisconnect(DisconnectionDetails details, CallbackInfo callback) {
        JaneLoginPreflight.Session session = jane$session;
        if (session != null) session.connectionClosed();
    }
}
