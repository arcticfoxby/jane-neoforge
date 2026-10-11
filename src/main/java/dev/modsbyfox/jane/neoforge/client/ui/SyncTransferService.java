package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.ResolutionPlan;
import dev.modsbyfox.jane.core.ServerProviderClient;
import dev.modsbyfox.jane.core.ServerProviderTransport;
import dev.modsbyfox.jane.core.StagedFileVerifier;
import dev.modsbyfox.jane.core.StagingWorkspace;
import dev.modsbyfox.jane.core.SyncSelection;
import dev.modsbyfox.jane.core.UpdatePlan;
import dev.modsbyfox.jane.neoforge.client.MinecraftProviderDownload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Background-only source resolution and exact-file staging for one Jane session. */
final class SyncTransferService {
    private SyncTransferService() { }

    static boolean serverRouteAvailable(JaneSyncSession session) {
        return session.context().provider() != null;
    }

    static void resolve(JaneSyncSession session, SyncSelection selection, BooleanSupplier cancelled)
            throws InterruptedException {
        List<Comparison.Result> comparisons = session.context().results();
        if (!session.beginResolution(comparisons.size())) return;
        ModrinthClient modrinth = new ModrinthClient();
        Map<String, ClientInstallCategory> categories = new HashMap<>();
        ResolutionPlan plan = ResolutionPlan.resolve(comparisons, target -> {
            if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
            ModrinthClient.Match match = modrinth.find(target);
            categories.put(target.modId(), match.category());
            return match.source();
        }, serverRouteAvailable(session), session::resolutionProgress);
        if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
        session.publishResolution(plan);
        selection.applyCategories(categories);
        session.selectModIds(selection.selectedModIds());
    }

    static void retryLookupFailures(JaneSyncSession session, SyncSelection selection,
                                    BooleanSupplier cancelled) throws InterruptedException {
        ResolutionPlan old = session.snapshot().resolution();
        if (old == null) throw new IllegalStateException("Resolution is not ready");
        ModrinthClient modrinth = new ModrinthClient();
        Map<String, ClientInstallCategory> categories = new HashMap<>();
        ResolutionPlan refreshed = old.retryLookupFailures(target -> {
            if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
            ModrinthClient.Match match = modrinth.find(target);
            categories.put(target.modId(), match.category());
            return match.source();
        }, serverRouteAvailable(session));
        if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
        session.publishRetryResolution(refreshed);
        selection.applyCategories(categories);
        session.selectModIds(selection.selectedModIds());
    }

    static UpdatePlan transfer(JaneSyncSession session, SyncSelection selection, Path gameDir,
                               StagingWorkspace workspace, ResolutionPlan.TransferGroup group,
                               ResolutionPlan.TransferRoute route, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        JaneSyncSession.Snapshot start = session.snapshot();
        if (!start.running() || start.activeGroup() != group || start.activeRoute() != route
                || start.resolution() == null) throw new IOException("Transfer route is not active");
        if (route == ResolutionPlan.TransferRoute.CURRENT_SERVER && !serverRouteAvailable(session))
            throw new IOException("Current server source is unavailable");
        ModrinthClient modrinth = new ModrinthClient();
        try {
            for (ResolutionPlan.Item item : start.queue(group)) {
                if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
                transferOne(session, gameDir, workspace, item, route, modrinth, cancelled);
            }
            if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
            session.finishTransfer(false, null);
            return workspace.prepareIfComplete(gameDir, session, cancelled, selection.selectedModIds());
        } catch (InterruptedException exception) {
            session.finishTransfer(true, null);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            if (session.snapshot().running()) session.finishTransfer(false, "transfer failed");
            throw exception;
        }
    }

    private static void transferOne(JaneSyncSession session, Path gameDir, StagingWorkspace workspace,
                                    ResolutionPlan.Item item, ResolutionPlan.TransferRoute route,
                                    ModrinthClient modrinth, BooleanSupplier cancelled)
            throws InterruptedException {
        ManifestEntry target = item.comparison().required();
        String id = target.modId();
        // A deterministic local name avoids trusting any remote filename as an install target.
        String name = ResolutionPlan.serverSource(target).name();
        Path part = workspace.directory().resolve(name + ".part");
        Path staged = workspace.directory().resolve(name);
        long[] received = {0};
        boolean prepared = false;
        try {
            session.update(id, JaneSyncSession.RuntimeState.RESOLVING, 0);
            UpdatePlan.Operation operation = operation(gameDir, item, name);
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS) || Files.exists(staged, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Staging destination is already occupied");
            prepared = true;
            session.update(id, JaneSyncSession.RuntimeState.DOWNLOADING, 0);
            LongConsumer progress = amount -> {
                received[0] = amount;
                session.update(id, JaneSyncSession.RuntimeState.DOWNLOADING, amount);
            };
            if (route == ResolutionPlan.TransferRoute.TRUSTED_SOURCE) {
                if (item.trustedSource() == null) throw new IOException("No exact trusted source");
                modrinth.download(item.trustedSource(), target, part, cancelled, progress);
            } else {
                var offer = session.context().provider();
                if (offer == null) throw new IOException("Current server source is unavailable");
                if (offer.transport() == ServerProviderTransport.SEPARATE_PORT)
                    ServerProviderClient.download(session.context().serverAddress(), offer, target, part,
                            cancelled, progress);
                else if (offer.transport() == ServerProviderTransport.MINECRAFT)
                    MinecraftProviderDownload.download(session.context().serverAddress(), offer, target, part,
                            cancelled, progress);
                else throw new IOException("Unknown current-server transport");
            }
            if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
            StagedFileVerifier.verifyAndStage(part, staged, target, session, cancelled);
            workspace.add(operation);
        } catch (IOException | RuntimeException exception) {
            removePart(part);
            if (cancelled.getAsBoolean()) throw new InterruptedException("Sync cancelled");
            if (session.snapshot().items().stream().anyMatch(state ->
                    state.item().comparison().required().modId().equals(id)
                            && state.state() == JaneSyncSession.RuntimeState.READY))
                throw new IllegalStateException("Verified staging operation could not be recorded", exception);
            session.update(id, prepared ? JaneSyncSession.RuntimeState.FAILED
                    : JaneSyncSession.RuntimeState.LOCAL_ERROR, received[0]);
        }
    }

    private static UpdatePlan.Operation operation(Path gameDir, ResolutionPlan.Item item, String newName)
            throws IOException {
        Comparison.Result comparison = item.comparison();
        if (comparison.status() == Comparison.Status.FILE_ERROR
                || item.availability() == ResolutionPlan.Availability.LOCAL_ERROR)
            throw new IOException("A local file error cannot be downloaded");
        Path destination = PathSafety.newJarInMods(gameDir, newName);
        Comparison.LocalMod local = comparison.local();
        String oldName = null;
        String oldHash = null;
        if (local != null) {
            Path old = PathSafety.existingJarInMods(gameDir, local.jar());
            oldName = PathSafety.safeExistingJarName(old.getFileName().toString());
            oldHash = comparison.localHash();
            if (oldHash == null || !oldHash.equals(Hashing.sha512(old)))
                throw new IOException("Local JAR changed after comparison");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                && (oldName == null || !oldName.equalsIgnoreCase(newName)))
            throw new IOException("Install destination is already occupied");
        return new UpdatePlan.Operation(comparison.required().modId(),
                local == null ? UpdatePlan.Kind.ADD : UpdatePlan.Kind.REPLACE,
                oldName, oldHash, PathSafety.safeJarName(newName), comparison.required().sha512(),
                comparison.required().fileSize());
    }

    private static void removePart(Path part) {
        try { Files.deleteIfExists(part); }
        catch (IOException ignored) { /* A failed cleanup remains in this isolated staging workspace. */ }
    }
}
