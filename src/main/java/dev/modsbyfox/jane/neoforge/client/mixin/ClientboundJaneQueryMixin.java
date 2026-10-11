package dev.modsbyfox.jane.neoforge.client.mixin;

import dev.modsbyfox.jane.neoforge.network.JaneLoginQueryPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Decode only Jane's bounded login query before vanilla discards unknown query bytes. */
@Mixin(ClientboundCustomQueryPacket.class)
public abstract class ClientboundJaneQueryMixin {
    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void jane$readPayload(ResourceLocation id, FriendlyByteBuf buffer,
            CallbackInfoReturnable<CustomQueryPayload> callback) {
        if (JaneLoginQueryPayload.ID.equals(id)) callback.setReturnValue(JaneLoginQueryPayload.read(buffer));
    }
}
