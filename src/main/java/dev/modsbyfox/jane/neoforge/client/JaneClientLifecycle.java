package dev.modsbyfox.jane.neoforge.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.OneShotExtraJoin;
import dev.modsbyfox.jane.core.OneShotJoinOverride;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.ServerIdentity;
import dev.modsbyfox.jane.core.SyncSelection;
import dev.modsbyfox.jane.neoforge.ClientManifestAssessment;
import dev.modsbyfox.jane.neoforge.NeoForgePhysicalDiscovery;
import dev.modsbyfox.jane.neoforge.client.ui.JaneClientUi;
import dev.modsbyfox.jane.neoforge.network.ResultPayload;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns one connection's Jane decision and never carries a consent to a different target. */
public final class JaneClientLifecycle {
    private record Target(long attempt, String address, String serverId,
                          ServerData data, Connection connection) { }
    private record Review(long attempt, PendingSyncContext context,
                          ClientCompatibilityReport extras, ServerData data, boolean auditSaveFailed) { }

    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private static final Object LOCK = new Object();
    private static final OneShotJoinOverride OVERRIDE = new OneShotJoinOverride();
    private static final OneShotExtraJoin EXTRA_JOIN = new OneShotExtraJoin();
    private static final Semaphore ASSESSMENT_SLOTS = new Semaphore(2);
    private static final ExecutorService ASSESSMENT_WORKERS = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("jane-client-assessment-", 0).factory());
    private static final JaneClientUi.JoinActions JOIN_ACTIONS = new JaneClientUi.JoinActions() {
        @Override
        public boolean reconnectWithExtras(PendingSyncContext context, ClientCompatibilityReport extras) {
            return JaneClientLifecycle.reconnectWithExtras(context, extras);
        }

        @Override
        public boolean reconnectWithRequiredOverride(PendingSyncContext context, SyncSelection selection) {
            return JaneClientLifecycle.reconnectWithRequiredOverride(context, selection);
        }

        @Override
        public Component failureNotice() {
            return joinFailureNotice;
        }
    };

    private static long nextAttempt;
    private static Target target;
    private static Review pending;
    private static Review activeReview;
    private static String armedServerId;
    private static long armedAttempt;
    private static Connection passedConnection;
    private static Connection preflightInFlight;
    private static JaneLoginPreflightMemo preflightResult;
    private static volatile Component joinFailureNotice =
            Component.translatable("jane.direct.reconnect_failed");
    private static long hudUntil;
    private static boolean startupChecked;
    private static boolean registered;

    private JaneClientLifecycle() { }

    /** Called only on the physical client from Jane's mod constructor. */
    public static void register() {
        synchronized (LOCK) {
            if (registered) return;
            registered = true;
        }
        NeoForge.EVENT_BUS.addListener(JaneClientLifecycle::onClientTick);
        NeoForge.EVENT_BUS.addListener(JaneClientLifecycle::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(JaneClientLifecycle::onRenderGui);
    }

    /** Called by a client-only ConnectScreen mixin before DNS/SRV resolution starts. */
    public static void captureConnectTarget(ServerData original) {
        Target captured = null;
        try {
            Objects.requireNonNull(original, "server data");
            String address = ServerIdentity.normalize(original.ip);
            ServerData data = new ServerData(original.name, original.ip, original.type());
            data.setResourcePackStatus(original.getResourcePackStatus());
            captured = new Target(0, address, ServerIdentity.id(address), data, null);
        } catch (RuntimeException exception) {
            LOGGER.warn("Jane could not capture the original server target: {}",
                    exception.getClass().getSimpleName());
        }
        synchronized (LOCK) {
            long attempt = ++nextAttempt;
            target = captured == null ? null : new Target(attempt, captured.address(),
                    captured.serverId(), captured.data(), null);
            pending = null;
            activeReview = null;
            passedConnection = null;
            preflightInFlight = null;
            preflightResult = null;
            if (target != null && armedServerId != null && armedAttempt == 0
                    && armedServerId.equals(target.serverId())) {
                armedAttempt = attempt;
            } else {
                clearAuthorizations();
            }
        }
    }

    /** Associate the original server target with exactly one Minecraft login connection. */
    public static void captureHandshakeConnection(Connection connection, ServerData data) {
        synchronized (LOCK) {
            if (connection == null || data == null || target == null || target.connection() != null) {
                invalidateTarget();
                return;
            }
            try {
                if (!target.address().equals(ServerIdentity.normalize(data.ip))) {
                    invalidateTarget();
                    return;
                }
            } catch (RuntimeException exception) {
                invalidateTarget();
                return;
            }
            target = new Target(target.attempt(), target.address(), target.serverId(),
                    target.data(), connection);
        }
    }

    public static CompletionStage<ResultPayload.Status> evaluate(ManifestCodec.LoginOffer offer,
            IPayloadContext context) throws IOException {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(context, "context");
        return evaluate(offer, context.connection(), false);
    }

    /** Evaluate the original login connection before NeoForge's configuration negotiation. */
    public static CompletionStage<ResultPayload.Status> evaluateLogin(ManifestCodec.LoginOffer offer,
            Connection connection) throws IOException {
        return evaluate(offer, connection, true);
    }

    private static CompletionStage<ResultPayload.Status> evaluate(ManifestCodec.LoginOffer offer,
            Connection connection, boolean loginPreflight) throws IOException {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(connection, "connection");
        byte[] manifestBytes = JaneLoginPreflightMemo.requiredBytes(offer);
        var gameDir = FMLPaths.GAMEDIR.get();
        boolean local = connection.isMemoryConnection();
        Target current;
        synchronized (LOCK) {
            if (local) {
                ++nextAttempt;
                target = null;
                pending = null;
                activeReview = null;
                preflightInFlight = null;
                preflightResult = null;
                clearAuthorizations();
            }
            current = target;
            if (!local && (current == null || current.connection() != connection))
                throw new IOException("Jane cannot bind the original server target to this connection");
            if (!local && preflightResult != null && preflightResult.owns(connection)) {
                if (loginPreflight) throw new IOException("Jane login preflight already completed");
                if (!connection.isConnected()) throw new IOException("Jane login connection closed");
                return CompletableFuture.completedFuture(preflightResult.reuse(connection, manifestBytes));
            }
            if (!local && preflightInFlight == connection)
                throw new IOException("Jane login preflight already in progress");
            if (loginPreflight) {
                if (local) throw new IOException("Jane login preflight requires a remote connection");
                preflightInFlight = connection;
            }
            if (!local && armedAttempt == current.attempt()) {
                OVERRIDE.beginConnection(connection);
                EXTRA_JOIN.beginConnection(connection);
            }
        }
        if (!ASSESSMENT_SLOTS.tryAcquire()) {
            clearPendingPreflight(connection, loginPreflight);
            throw new IOException("Jane client assessment is busy");
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    List<NeoForgePhysicalDiscovery.PhysicalJar> jars = NeoForgePhysicalDiscovery.discover(gameDir);
                    List<Comparison.Result> results = ClientManifestAssessment.assess(gameDir, offer.manifest(), jars);
                    if (local) {
                        if (!connection.isConnected() || !Comparison.passed(results)) {
                            LOGGER.warn("Jane local-world required comparison did not pass");
                            return ResultPayload.Status.PROTOCOL_ERROR;
                        }
                        return ResultPayload.Status.PASS;
                    }
                    ClientCompatibilityReport extras = NeoForgeClientCompatibility.classify(gameDir,
                            offer.manifest(), jars);
                    return decide(current, connection, offer, results, extras);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, ASSESSMENT_WORKERS).whenComplete((status, error) -> {
                ASSESSMENT_SLOTS.release();
                if (loginPreflight) {
                    synchronized (LOCK) {
                        if (preflightInFlight == connection) preflightInFlight = null;
                        if (error == null && (status == ResultPayload.Status.PASS
                                || status == ResultPayload.Status.USER_OVERRIDE)
                                && target == current && connection.isConnected()) {
                            preflightResult = new JaneLoginPreflightMemo(connection, manifestBytes, status);
                        }
                    }
                }
            });
        } catch (RuntimeException exception) {
            ASSESSMENT_SLOTS.release();
            clearPendingPreflight(connection, loginPreflight);
            throw exception;
        }
    }

    private static void clearPendingPreflight(Connection connection, boolean loginPreflight) {
        if (!loginPreflight) return;
        synchronized (LOCK) {
            if (preflightInFlight == connection) preflightInFlight = null;
        }
    }

    /** Prevent a timed-out or invalid login query from producing a late review or reused permission. */
    public static void abortLogin(Connection connection) {
        synchronized (LOCK) {
            if (target != null && target.connection() == connection) invalidateTarget();
        }
    }

    private static ResultPayload.Status decide(Target current, Connection connection,
            ManifestCodec.LoginOffer offer, List<Comparison.Result> results,
            ClientCompatibilityReport extras) throws IOException {
        logAssessment(current.serverId(), results, extras);
        boolean auditSaveFailed = !auditAssessment(current.serverId(), offer, results, extras);
        synchronized (LOCK) {
            if (target != current || !connection.isConnected()) return ResultPayload.Status.PROTOCOL_ERROR;
            String serverId = current.serverId();
            if (Comparison.passed(results)) {
                OVERRIDE.clearForConnection(connection);
                if (!OneShotExtraJoin.hasExtras(extras)) {
                    EXTRA_JOIN.clearForConnection(connection);
                    clearArmedTarget();
                    passedConnection = connection;
                    return ResultPayload.Status.PASS;
                }
                if (armedAttempt == current.attempt()
                        && EXTRA_JOIN.consume(connection, serverId, offer.manifest(), extras)) {
                    clearArmedTarget();
                    passedConnection = connection;
                    LOGGER.info("Jane direct join with unchanged client extras, serverId={}", shortId(serverId));
                    return ResultPayload.Status.PASS;
                }
            } else {
                EXTRA_JOIN.clearForConnection(connection);
                if (armedAttempt == current.attempt()
                        && OVERRIDE.consume(connection, serverId, offer.manifest(), results)) {
                    clearArmedTarget();
                    LOGGER.warn("Jane USER_OVERRIDE on serverId={}: required baseline not satisfied",
                            shortId(serverId));
                    return ResultPayload.Status.USER_OVERRIDE;
                }
            }
            clearAuthorizations();
            pending = new Review(current.attempt(), new PendingSyncContext(current.address(),
                    offer.manifest(), results, offer.provider()), extras, current.data(), auditSaveFailed);
            return ResultPayload.Status.ACTION_REQUIRED;
        }
    }

    private static boolean auditAssessment(String serverId, ManifestCodec.LoginOffer offer,
            List<Comparison.Result> results, ClientCompatibilityReport extras) {
        long missing = results.stream().filter(result -> result.status() == Comparison.Status.MISSING).count();
        long version = results.stream().filter(result -> result.status() == Comparison.Status.VERSION_MISMATCH).count();
        long hash = results.stream().filter(result -> result.status() == Comparison.Status.HASH_MISMATCH).count();
        long errors = results.stream().filter(result -> result.status() == Comparison.Status.FILE_ERROR).count();
        int extraCount = extras.explicitClientMods().size() + extras.otherExtraMods().size();
        String digest = OneShotJoinOverride.manifestDigest(offer.manifest());
        boolean saved = recordAudit("REQUIRED_COMPARISON_COMPLETED", Map.of(
                "serverId", serverId, "manifestDigest", digest,
                "required", Integer.toString(results.size()), "missing", Long.toString(missing),
                "versionMismatch", Long.toString(version), "hashMismatch", Long.toString(hash),
                "fileError", Long.toString(errors)));
        saved &= recordAudit("ENVIRONMENT_CHECK_COMPLETED", Map.of(
                "serverId", serverId, "manifestDigest", digest,
                "required", Integer.toString(results.size()),
                "matched", Long.toString(results.size() - missing - version - hash - errors),
                "missing", Long.toString(missing), "versionMismatch", Long.toString(version),
                "hashMismatch", Long.toString(hash), "fileError", Long.toString(errors),
                "extraClientMods", Integer.toString(extraCount)));
        if (extraCount > 0) saved &= recordAudit("EXTRA_CLIENT_MOD_WARNING", Map.of(
                "serverId", serverId, "requiredBaseline", Comparison.passed(results) ? "PASS" : "NOT_SATISFIED",
                "extraClientMods", Integer.toString(extraCount)));
        return saved;
    }

    private static void logAssessment(String serverId, List<Comparison.Result> results,
            ClientCompatibilityReport extras) {
        long missing = results.stream().filter(result -> result.status() == Comparison.Status.MISSING).count();
        long version = results.stream().filter(result -> result.status() == Comparison.Status.VERSION_MISMATCH).count();
        long hash = results.stream().filter(result -> result.status() == Comparison.Status.HASH_MISMATCH).count();
        long errors = results.stream().filter(result -> result.status() == Comparison.Status.FILE_ERROR).count();
        LOGGER.info("Jane environment serverId={} required={} missing={} versionMismatch={} "
                        + "hashMismatch={} fileError={} extraClientMods={}", shortId(serverId), results.size(),
                missing, version, hash, errors,
                extras.explicitClientMods().size() + extras.otherExtraMods().size());
    }

    /** Test and diagnostics seam; UI normally consumes the full review after disconnect. */
    static PendingSyncContext takePending() {
        synchronized (LOCK) {
            Review review = pending;
            pending = null;
            return review == null ? null : review.context();
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        Review review;
        synchronized (LOCK) { review = pending; }
        if (review != null && client.level == null && client.screen instanceof DisconnectedScreen) {
            joinFailureNotice = Component.translatable("jane.direct.reconnect_failed");
            boolean opened = JaneClientUi.openReview(client.screen, review.context(), review.extras(),
                    review.auditSaveFailed(), JOIN_ACTIONS);
            if (opened) {
                synchronized (LOCK) {
                    if (pending == review) {
                        pending = null;
                        activeReview = review;
                    }
                }
            }
        }
        if (!startupChecked && client.screen instanceof TitleScreen) {
            startupChecked = true;
            var parent = client.screen;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return PendingRecovery.scan(FMLPaths.GAMEDIR.get());
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }).whenComplete((items, failure) -> client.execute(() -> {
                if (failure != null) {
                    LOGGER.warn("Jane pending recovery scan failed", failure);
                } else if (!items.isEmpty() && client.screen == parent) {
                    JaneClientUi.openRecovery(parent, items.get(0));
                }
            }));
        }
        if (client.screen instanceof DisconnectedScreen && review == null) {
            synchronized (LOCK) {
                if (armedAttempt != 0 && pending == null) clearAuthorizations();
            }
        }
    }

    public static void recheckPendingRecovery() {
        startupChecked = false;
    }

    private static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        synchronized (LOCK) {
            if (passedConnection == event.getConnection()) hudUntil = System.currentTimeMillis() + 4_000;
            passedConnection = null;
            pending = null;
            activeReview = null;
            preflightInFlight = null;
            preflightResult = null;
            clearAuthorizations();
        }
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        long remaining = hudUntil - System.currentTimeMillis();
        if (remaining <= 0) return;
        Minecraft client = Minecraft.getInstance();
        Component message = Component.translatable("jane.hud.aligned");
        int alpha = remaining < 1_000 ? (int) (remaining * 255 / 1_000) : 255;
        int x = client.getWindow().getGuiScaledWidth() - client.font.width(message) - 10;
        int y = client.getWindow().getGuiScaledHeight() - 24;
        event.getGuiGraphics().drawString(client.font, message, x, y, (alpha << 24) | 0xFFFFFF);
    }

    private static boolean reconnectWithExtras(PendingSyncContext context,
            ClientCompatibilityReport extras) {
        joinFailureNotice = Component.translatable("jane.direct.reconnect_failed");
        Review review;
        synchronized (LOCK) {
            review = activeReview;
            if (review == null || review.context() != context || review.extras() != extras
                    || !Comparison.passed(context.results()) || !OneShotExtraJoin.hasExtras(extras)
                    || !sameTarget(review, context)) return false;
            if (!EXTRA_JOIN.arm(context.serverId(), context.manifest(), extras)) return false;
            int count = extras.explicitClientMods().size() + extras.otherExtraMods().size();
            if (!recordAudit("DIRECT_JOIN_CONFIRMED", Map.of(
                    "serverId", context.serverId(),
                    "manifestDigest", OneShotJoinOverride.manifestDigest(context.manifest()),
                    "requiredBaseline", "PASS", "extraClientMods", Integer.toString(count),
                    "preservedExtras", Integer.toString(count)))) {
                EXTRA_JOIN.clear();
                joinFailureNotice = Component.translatable("jane.select.audit_incomplete");
                return false;
            }
            OVERRIDE.clear();
            armedServerId = context.serverId();
            armedAttempt = 0;
        }
        return reconnect(review);
    }

    private static boolean reconnectWithRequiredOverride(PendingSyncContext context, SyncSelection selection) {
        joinFailureNotice = Component.translatable("jane.direct.reconnect_failed");
        Review review;
        synchronized (LOCK) {
            review = activeReview;
            if (review == null || review.context() != context || selection == null
                    || !sameTarget(review, context)
                    || !OneShotJoinOverride.overrideable(context.manifest(), context.results())) return false;
            if (!OVERRIDE.arm(context.serverId(), context.manifest(), selection, context.results())) return false;
            long skipped = context.results().stream()
                    .filter(result -> result.status() != Comparison.Status.OK).count();
            boolean saved = recordAudit("USER_OVERRIDE_CONFIRMED", Map.of(
                    "serverId", context.serverId(),
                    "manifestDigest", OneShotJoinOverride.manifestDigest(context.manifest()),
                    "requiredBaseline", "NOT_SATISFIED", "skippedRequired", Long.toString(skipped)));
            for (Comparison.Result result : context.results()) {
                if (result.status() == Comparison.Status.OK) continue;
                saved &= recordAudit("SKIPPED_REQUIRED", Map.of(
                        "serverId", context.serverId(), "modId", result.required().modId(),
                        "requiredVersion", result.required().version(),
                        "comparisonStatus", result.status().name(),
                        "requiredHash", result.required().sha512().substring(0, 12)));
            }
            if (!saved) {
                OVERRIDE.clear();
                joinFailureNotice = Component.translatable("jane.select.audit_incomplete");
                return false;
            }
            EXTRA_JOIN.clear();
            armedServerId = context.serverId();
            armedAttempt = 0;
        }
        return reconnect(review);
    }

    private static boolean reconnect(Review review) {
        Minecraft client = Minecraft.getInstance();
        try {
            ConnectScreen.startConnecting(new TitleScreen(), client,
                    ServerAddress.parseString(review.data().ip), review.data(), false, null);
            synchronized (LOCK) {
                if (armedAttempt == 0) {
                    clearAuthorizations();
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            synchronized (LOCK) { clearAuthorizations(); }
            LOGGER.warn("Jane authorized reconnect failed", exception);
            return false;
        }
    }

    private static boolean sameTarget(Review review, PendingSyncContext context) {
        try {
            return review.attempt() == target.attempt()
                    && ServerIdentity.normalize(review.data().ip).equals(context.serverAddress());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean recordAudit(String event, Map<String, String> fields) {
        try {
            JaneClientAudit.record(FMLPaths.GAMEDIR.get(), event, fields);
            return true;
        } catch (IOException | SecurityException exception) {
            LOGGER.error("Jane decision audit could not save {}: {}", event,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private static void clearArmedTarget() {
        armedServerId = null;
        armedAttempt = 0;
    }

    private static void clearAuthorizations() {
        OVERRIDE.clear();
        EXTRA_JOIN.clear();
        clearArmedTarget();
    }

    private static void invalidateTarget() {
        target = null;
        pending = null;
        activeReview = null;
        passedConnection = null;
        preflightInFlight = null;
        preflightResult = null;
        clearAuthorizations();
    }

    private static String shortId(String serverId) {
        return serverId.substring(0, Math.min(serverId.length(), 12));
    }
}
