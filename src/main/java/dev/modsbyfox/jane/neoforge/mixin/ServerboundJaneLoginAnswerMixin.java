package dev.modsbyfox.jane.neoforge.mixin;

import dev.modsbyfox.jane.neoforge.network.JaneLoginAnswerPayload;
import dev.modsbyfox.jane.neoforge.network.JaneLoginQueryPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla discards unknown login answers, so decode only Jane's reserved transaction. */
@Mixin(ServerboundCustomQueryAnswerPacket.class)
public abstract class ServerboundJaneLoginAnswerMixin {
    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void jane$readAnswer(int transactionId, FriendlyByteBuf buffer,
            CallbackInfoReturnable<CustomQueryAnswerPayload> callback) {
        if (transactionId != JaneLoginQueryPayload.TRANSACTION_ID) return;
        callback.setReturnValue(JaneLoginAnswerPayload.readNullable(buffer));
    }
}
