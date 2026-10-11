package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ServerProviderCore;
import dev.modsbyfox.jane.core.ServerProviderOffer;
import dev.modsbyfox.jane.core.ServerProviderService;
import dev.modsbyfox.jane.neoforge.network.JaneConfigurationNetwork;
import dev.modsbyfox.jane.neoforge.network.JaneLoginPreflight;
import dev.modsbyfox.jane.neoforge.network.ResultPayload;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common NeoForge entrypoint; the client adapter is loaded only on a physical client. */
@Mod(JaneMod.MOD_ID)
public final class JaneMod {
    public static final String MOD_ID = "jane";
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private static final Semaphore MANIFEST_SLOTS = new Semaphore(2);
    private static final ExecutorService MANIFEST_WORKERS = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("jane-manifest-", 0).factory());
    private static volatile MinecraftProviderTransport activeMinecraftProvider;
    private volatile ProviderRuntime providerRuntime;

    public JaneMod(IEventBus modBus) {
        JaneConfigurationNetwork.ClientEvaluator evaluator = FMLEnvironment.dist == Dist.CLIENT
                ? ClientDelegate::evaluate : null;
        JaneConfigurationNetwork.register(modBus, this::prepareManifest, evaluator);
        JaneLoginPreflight.register(this::prepareManifest);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.modsbyfox.jane.neoforge.client.JaneClientLifecycle.register();
        }
    }

    /** Prepare the dedicated-server file snapshot before configuration logins begin. */
    private void onServerStarting(ServerStartingEvent event) {
        if (!event.getServer().isDedicatedServer()) return;
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            ServerManifest.Prepared prepared = ServerManifest.prepare(gameDir);
            String version = ModList.get().getModContainerById(MOD_ID)
                    .orElseThrow(() -> new IllegalStateException("Jane mod metadata is unavailable"))
                    .getModInfo().getVersion().toString();
            DiscoveredModsStore.write(gameDir, version, prepared.classified());
            JaneProviderConfig config = JaneProviderConfig.read(gameDir);
            providerRuntime = ProviderRuntime.start(gameDir, prepared, config);
            LOGGER.info("Jane server manifest prepared: physicalJars={} requiredEntries={} provider={}",
                    prepared.classified().size(), prepared.manifest().entries().size(), config.mode());
        } catch (IOException | RuntimeException exception) {
            onServerStopping(null);
            throw new IllegalStateException("Jane server preparation failed closed", exception);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ProviderRuntime old = providerRuntime;
        providerRuntime = null;
        activeMinecraftProvider = null;
        if (old != null) old.close();
    }

    public static void acceptMarkedTransfer(net.minecraft.server.MinecraftServer server,
                                            net.minecraft.network.Connection connection) {
        MinecraftProviderTransport current = activeMinecraftProvider;
        if (current == null || !server.isDedicatedServer()) {
            if (connection.channel() != null) connection.channel().close();
            return;
        }
        current.takeOver(server, connection);
    }

    private CompletableFuture<ManifestCodec.LoginOffer> prepareManifest() throws IOException {
        if (!MANIFEST_SLOTS.tryAcquire()) {
            throw new IOException("Jane manifest preparation is busy");
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Path gameDir = FMLPaths.GAMEDIR.get();
                    JaneRequirementConfig config = JaneRequirementConfig.read(gameDir);
                    List<NeoForgePhysicalDiscovery.PhysicalJar> jars = NeoForgePhysicalDiscovery.discover(gameDir);
                    ServerManifest.Prepared prepared = ServerManifest.prepare(gameDir, jars,
                            (jar, sha512) -> new ServerManifest.Evidence(config.forJar(jar), null),
                            config.identity());
                    ProviderRuntime runtime = providerRuntime;
                    ServerProviderOffer provider = null;
                    if (runtime != null) {
                        if (!runtime.manifest.entries().equals(prepared.manifest().entries()))
                            throw new IOException("Jane server manifest changed since provider startup; restart required");
                        provider = runtime.offer(prepared.manifest());
                    }
                    return new ManifestCodec.LoginOffer(prepared.manifest(), provider);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, MANIFEST_WORKERS).whenComplete((offer, failure) -> MANIFEST_SLOTS.release());
        } catch (RuntimeException exception) {
            MANIFEST_SLOTS.release();
            throw exception;
        }
    }

    private static final class ProviderRuntime implements AutoCloseable {
        final RequiredManifest manifest;
        final MinecraftProviderTransport minecraft;
        final ServerProviderService separate;
        final int advertisedPort;

        private ProviderRuntime(RequiredManifest manifest, MinecraftProviderTransport minecraft,
                                ServerProviderService separate, int advertisedPort) {
            this.manifest = manifest;
            this.minecraft = minecraft;
            this.separate = separate;
            this.advertisedPort = advertisedPort;
        }

        static ProviderRuntime start(Path gameDir, ServerManifest.Prepared prepared,
                                     JaneProviderConfig config) throws IOException {
            if (config.mode() == JaneProviderConfig.Mode.DISABLED)
                return new ProviderRuntime(prepared.manifest(), null, null, 0);
            Map<String, ServerProviderCore.File> files = new HashMap<>();
            for (var entry : prepared.manifest().entries()) {
                Path file = prepared.filesByHash().get(entry.sha512());
                if (file == null) throw new IOException("Provider file is missing from prepared manifest");
                files.put(entry.sha512(), new ServerProviderCore.File(entry, file));
            }
            if (config.mode() == JaneProviderConfig.Mode.SEPARATE_PORT) {
                Map<String, ServerProviderService.File> served = new HashMap<>();
                for (var item : files.entrySet())
                    served.put(item.getKey(), new ServerProviderService.File(item.getValue().entry(),
                            item.getValue().path()));
                ServerProviderService service = new ServerProviderService(gameDir, served,
                        config.bindPort(), Clock.systemUTC());
                return new ProviderRuntime(prepared.manifest(), null, service, config.advertisedPort());
            }
            MinecraftProviderTransport service = new MinecraftProviderTransport(
                    new ServerProviderCore(gameDir, files, Clock.systemUTC()));
            activeMinecraftProvider = service;
            return new ProviderRuntime(prepared.manifest(), service, null, 0);
        }

        ServerProviderOffer offer(RequiredManifest currentManifest) {
            if (minecraft != null) return ServerProviderOffer.minecraft(minecraft.issue(currentManifest));
            if (separate != null)
                return ServerProviderOffer.separatePort(advertisedPort, separate.issue(currentManifest));
            return null;
        }

        @Override public void close() {
            if (minecraft != null) minecraft.close();
            if (separate != null) separate.close();
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
