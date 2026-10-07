package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.neoforge.network.JaneConfigurationNetwork;
import dev.modsbyfox.jane.neoforge.network.ResultPayload;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Common NeoForge entrypoint; the client adapter is loaded only on a physical client. */
@Mod(JaneMod.MOD_ID)
public final class JaneMod {
    public static final String MOD_ID = "jane";
    private static final Semaphore MANIFEST_SLOTS = new Semaphore(2);
    private static final ExecutorService MANIFEST_WORKERS = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("jane-manifest-", 0).factory());

    public JaneMod(IEventBus modBus) {
        JaneConfigurationNetwork.ClientEvaluator evaluator = FMLEnvironment.dist == Dist.CLIENT
                ? ClientDelegate::evaluate : null;
        JaneConfigurationNetwork.register(modBus, JaneMod::prepareManifest, evaluator);
    }

    private static CompletableFuture<ManifestCodec.LoginOffer> prepareManifest() throws IOException {
        if (!MANIFEST_SLOTS.tryAcquire()) {
            throw new IOException("Jane manifest preparation is busy");
        }
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            JaneRequirementConfig config = JaneRequirementConfig.read(gameDir);
            List<NeoForgePhysicalDiscovery.PhysicalJar> jars = NeoForgePhysicalDiscovery.discover(gameDir);
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return new ManifestCodec.LoginOffer(ServerManifest.prepare(gameDir, jars,
                            (jar, sha512) -> new ServerManifest.Evidence(config.forJar(jar), null)).manifest(), null);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, MANIFEST_WORKERS).whenComplete((offer, failure) -> MANIFEST_SLOTS.release());
        } catch (IOException | RuntimeException exception) {
            MANIFEST_SLOTS.release();
            throw exception;
        }
    }

    private static final class ClientDelegate {
        private static CompletionStage<ResultPayload.Status> evaluate(
                ManifestCodec.LoginOffer offer, IPayloadContext context)
                throws IOException {
            return dev.modsbyfox.jane.neoforge.client.JaneClientAssessment.evaluate(offer, context);
        }
    }
}
