package dev.modsbyfox.jane.neoforge.client.mixin;

import dev.modsbyfox.jane.neoforge.client.JaneClientLifecycle;
import dev.modsbyfox.jane.neoforge.client.JaneClientLoginPreflight;
import dev.modsbyfox.jane.neoforge.network.JaneLoginQueryPayload;
import java.time.Duration;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bind the captured server address to the connection used for this login. */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakeCaptureMixin {
    @Shadow @Final private Connection connection;
    @Unique private JaneClientLoginPreflight jane$loginPreflight;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void jane$captureConnection(Connection connection, Minecraft client,
            ServerData data, Screen parent, boolean newWorld, Duration worldLoadDuration,
            Consumer<Component> updateStatus, TransferState transferState, CallbackInfo callback) {
        jane$loginPreflight = new JaneClientLoginPreflight();
        JaneClientLifecycle.captureHandshakeConnection(connection, data);
    }

    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void jane$handleLoginPreflight(ClientboundCustomQueryPacket packet, CallbackInfo callback) {
        if (!JaneLoginQueryPayload.ID.equals(packet.payload().id())) return;
        callback.cancel();
        jane$loginPreflight.handle(connection, (ClientHandshakePacketListenerImpl) (Object) this, packet);
    }
}
