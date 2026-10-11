package dev.modsbyfox.jane.neoforge.mixin;

import dev.modsbyfox.jane.neoforge.network.JaneConfigurationNetwork;
import java.util.Queue;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Queue Jane's check before NeoForge and vanilla registry synchronization. */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationEarlyTaskMixin {
    @Shadow @Final private Queue<ConfigurationTask> configurationTasks;

    @Inject(method = "runConfiguration", at = @At("HEAD"), cancellable = true)
    private void jane$queueEarlyTask(CallbackInfo callback) {
        ServerConfigurationPacketListener listener = (ServerConfigurationPacketListener) (Object) this;
        if (!JaneConfigurationNetwork.installEarlyTask(listener, configurationTasks::add)) {
            callback.cancel();
        }
    }
}
