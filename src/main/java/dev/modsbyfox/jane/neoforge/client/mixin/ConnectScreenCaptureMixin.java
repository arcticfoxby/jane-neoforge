package dev.modsbyfox.jane.neoforge.client.mixin;

import dev.modsbyfox.jane.neoforge.client.JaneClientLifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve the player's original server target before DNS/SRV resolution. */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenCaptureMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void jane$captureServerTarget(Screen parent, Minecraft client,
            ServerAddress address, ServerData data, boolean quickPlay,
            @Nullable TransferState transferState, CallbackInfo callback) {
        JaneClientLifecycle.captureConnectTarget(data);
    }
}
