package dev.modsbyfox.jane.neoforge.network;

import dev.modsbyfox.jane.core.ManifestCodec;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Runs Jane's fail-closed requirement check before the player enters the world. */
public final class JaneConfigurationNetwork {
    @FunctionalInterface
    public interface ManifestSupplier {
        /** Snapshot platform state on the configuration thread, then prepare hashes asynchronously. */
        CompletionStage<ManifestCodec.LoginOffer> get() throws IOException;
    }

    @FunctionalInterface
    public interface ClientEvaluator {
        /** Persist any pending review before completing with ACTION_REQUIRED. */
        CompletionStage<ResultPayload.Status> evaluate(ManifestCodec.LoginOffer offer, IPayloadContext context)
                throws IOException;
    }

    private enum Phase { PREPARING, SENT, TERMINAL }

    private static final ConfigurationTask.Type TASK_TYPE = new ConfigurationTask.Type(
            ResourceLocation.fromNamespaceAndPath("jane", "required_environment"));
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(3);
    private static final long CLEANUP_GRACE_MILLIS = 1_000;
    private static final ScheduledThreadPoolExecutor TIMEOUTS = createTimeoutExecutor();
    private static final Component MISSING_CLIENT = Component.literal("Jane is required on this client to join this server.");
    private static final Component SERVER_FAILURE = Component.literal("Jane could not prepare the required mod manifest.");
    private static final Component INVALID_MANIFEST = Component.literal("Jane could not read the server's required mod manifest.");
    private static final Component ACTION_REQUIRED = Component.literal("Jane requires mod changes before joining. Apply them, restart Minecraft, and reconnect.");
    private static final Component PROTOCOL_ERROR = Component.literal("Jane protocol mismatch or invalid configuration data.");

    private final ManifestSupplier manifestSupplier;
    private final ClientEvaluator clientEvaluator;
    private final long timeoutMillis;
    private final BiConsumer<ServerConfigurationPacketListener, Runnable> serverScheduler;
    private final ConcurrentMap<Connection, ServerState> serverStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<Connection, ClientState> clientStates = new ConcurrentHashMap<>();

    JaneConfigurationNetwork(ManifestSupplier manifestSupplier, ClientEvaluator clientEvaluator) {
        this(manifestSupplier, clientEvaluator, DEFAULT_TIMEOUT,
                (listener, task) -> listener.getMainThreadEventLoop().execute(task));
    }

    JaneConfigurationNetwork(ManifestSupplier manifestSupplier, ClientEvaluator clientEvaluator, Duration timeout,
            BiConsumer<ServerConfigurationPacketListener, Runnable> serverScheduler) {
        this.manifestSupplier = Objects.requireNonNull(manifestSupplier, "manifestSupplier");
        this.clientEvaluator = clientEvaluator;
        this.timeoutMillis = Objects.requireNonNull(timeout, "timeout").toMillis();
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeout must be at least one millisecond");
        this.serverScheduler = Objects.requireNonNull(serverScheduler, "serverScheduler");
    }

    /** The evaluator may be null on a physical dedicated server, where client code is unavailable. */
    public static void register(IEventBus modBus, ManifestSupplier manifestSupplier, ClientEvaluator clientEvaluator) {
        Objects.requireNonNull(modBus, "modBus");
        JaneConfigurationNetwork network = new JaneConfigurationNetwork(manifestSupplier, clientEvaluator);
        modBus.addListener(network::registerPayloads);
        modBus.addListener(network::registerTasks);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Optional registration lets this task provide a clear missing-Jane reason after negotiation.
        PayloadRegistrar registrar = event.registrar("3").optional();
        registrar.configurationToClient(ManifestPayload.TYPE, ManifestPayload.STREAM_CODEC, this::handleManifest);
        registrar.configurationToServer(ResultPayload.TYPE, ResultPayload.STREAM_CODEC, this::handleResult);
    }

    private void registerTasks(RegisterConfigurationTasksEvent event) {
        ServerConfigurationPacketListener listener = event.getListener();
        try {
            if (!listener.hasChannel(ManifestPayload.TYPE) || !listener.hasChannel(ResultPayload.TYPE)) {
                listener.disconnect(MISSING_CLIENT);
                return;
            }
            event.register(new ManifestTask(listener));
        } catch (RuntimeException exception) {
            listener.disconnect(SERVER_FAILURE);
        }
    }

    void handleManifest(ManifestPayload payload, IPayloadContext context) {
        ICommonPacketListener listener;
        Connection connection;
        try {
            listener = context.listener();
            connection = listener.getConnection();
            if (!isCurrent(listener, connection)) return;
        } catch (RuntimeException exception) {
            context.disconnect(PROTOCOL_ERROR);
            return;
        }

        ClientState state = new ClientState(listener, connection);
        ClientState previous = clientStates.putIfAbsent(connection, state);
        if (previous != null) {
            closeClient(previous);
            failClient(context, PROTOCOL_ERROR);
            return;
        }
        try {
            awaitClientCleanup(state);
        } catch (RuntimeException exception) {
            closeClient(state);
            failClient(context, PROTOCOL_ERROR);
            return;
        }

        ManifestCodec.LoginOffer offer;
        try {
            offer = ManifestCodec.decodeOffer(payload.bytes());
        } catch (IOException | RuntimeException exception) {
            closeClient(state);
            failClient(context, INVALID_MANIFEST);
            return;
        }

        CompletionStage<ResultPayload.Status> stage;
        try {
            if (clientEvaluator == null) throw new IllegalStateException("Missing Jane client evaluator");
            stage = Objects.requireNonNull(clientEvaluator.evaluate(offer, context), "evaluation stage");
        } catch (IOException | RuntimeException exception) {
            closeClient(state);
            failClient(context, PROTOCOL_ERROR);
            return;
        }

        watch(stage, (status, failure) -> {
            try {
                context.enqueueWork(() -> {
                    try {
                        completeClient(state, context, status, failure);
                    } catch (RuntimeException exception) {
                        closeClient(state);
                        if (isCurrent(state.listener, state.connection)) state.connection.disconnect(PROTOCOL_ERROR);
                    }
                })
                        .exceptionally(error -> {
                            abortClient(state);
                            return null;
                        });
            } catch (RuntimeException exception) {
                abortClient(state);
            }
        });
    }

    private void completeClient(ClientState state, IPayloadContext context, ResultPayload.Status status, Throwable failure) {
        if (!state.phase.compareAndSet(Phase.PREPARING, Phase.TERMINAL)) return;
        clientStates.remove(state.connection, state);
        state.cancelTimeout();
        if (!isCurrent(state.listener, state.connection)) return;
        if (failure != null || status == null) {
            failClient(context, PROTOCOL_ERROR);
            return;
        }
        try {
            context.reply(new ResultPayload(status));
        } catch (RuntimeException exception) {
            context.disconnect(PROTOCOL_ERROR);
            return;
        }
        if (status == ResultPayload.Status.ACTION_REQUIRED) {
            context.disconnect(ACTION_REQUIRED);
        } else if (status == ResultPayload.Status.PROTOCOL_ERROR) {
            context.disconnect(PROTOCOL_ERROR);
        }
    }

    private void abortClient(ClientState state) {
        if (!state.phase.compareAndSet(Phase.PREPARING, Phase.TERMINAL)) return;
        clientStates.remove(state.connection, state);
        state.cancelTimeout();
        if (isCurrent(state.listener, state.connection)) state.connection.disconnect(PROTOCOL_ERROR);
    }

    private void awaitClientCleanup(ClientState state) {
        state.setTimeout(TIMEOUTS.schedule(() -> {
                    if (!state.phase.compareAndSet(Phase.PREPARING, Phase.TERMINAL)) return;
                    clientStates.remove(state.connection, state);
                    if (isCurrent(state.listener, state.connection)) state.connection.disconnect(PROTOCOL_ERROR);
                }, timeoutMillis + CLEANUP_GRACE_MILLIS, TimeUnit.MILLISECONDS));
    }

    private void closeClient(ClientState state) {
        state.phase.set(Phase.TERMINAL);
        clientStates.remove(state.connection, state);
        state.cancelTimeout();
    }

    private static void failClient(IPayloadContext context, Component reason) {
        try {
            context.reply(new ResultPayload(ResultPayload.Status.PROTOCOL_ERROR));
        } catch (RuntimeException ignored) {
            // A failed reply must not leave configuration pending.
        }
        context.disconnect(reason);
    }

    void handleResult(ResultPayload payload, IPayloadContext context) {
        ICommonPacketListener listener;
        Connection connection;
        try {
            listener = context.listener();
            connection = listener.getConnection();
        } catch (RuntimeException exception) {
            context.disconnect(PROTOCOL_ERROR);
            return;
        }
        ServerState state = serverStates.get(connection);
        if (state == null || state.listener != listener || !state.phase.compareAndSet(Phase.SENT, Phase.TERMINAL)) {
            if (state != null) closeServer(state);
            context.disconnect(PROTOCOL_ERROR);
            return;
        }
        serverStates.remove(connection, state);
        state.cancelTimeout();
        if (!isCurrent(listener, connection)) return;
        try {
            switch (payload.status()) {
                case PASS -> context.finishCurrentTask(TASK_TYPE);
                case ACTION_REQUIRED -> context.disconnect(ACTION_REQUIRED);
                case PROTOCOL_ERROR -> context.disconnect(PROTOCOL_ERROR);
            }
        } catch (RuntimeException exception) {
            context.disconnect(PROTOCOL_ERROR);
        }
    }

    private void completeServer(ServerState state, Consumer<CustomPacketPayload> sender,
            ManifestCodec.LoginOffer offer, Throwable failure) {
        if (state.phase.get() != Phase.PREPARING) return;
        if (!isCurrent(state.listener, state.connection)) {
            closeServer(state);
            return;
        }
        if (failure != null || offer == null) {
            failServer(state);
            return;
        }
        try {
            ManifestPayload payload = new ManifestPayload(ManifestCodec.encode(offer));
            if (!state.phase.compareAndSet(Phase.PREPARING, Phase.SENT)) return;
            sender.accept(payload);
            awaitServerResult(state);
        } catch (IOException | RuntimeException exception) {
            closeServer(state);
            state.listener.disconnect(SERVER_FAILURE);
        }
    }

    private void awaitServerResult(ServerState state) {
        state.setTimeout(TIMEOUTS.schedule(() -> timeoutServerResult(state), timeoutMillis, TimeUnit.MILLISECONDS));
    }

    private void timeoutServerResult(ServerState state) {
        if (!state.phase.compareAndSet(Phase.SENT, Phase.TERMINAL)) return;
        serverStates.remove(state.connection, state);
        state.cancelTimeout();
        if (isCurrent(state.listener, state.connection)) state.connection.disconnect(PROTOCOL_ERROR);
    }

    private void awaitServerCleanup(ServerState state) {
        state.setTimeout(TIMEOUTS.schedule(() -> {
                    if (!state.phase.compareAndSet(Phase.PREPARING, Phase.TERMINAL)) return;
                    serverStates.remove(state.connection, state);
                    if (isCurrent(state.listener, state.connection)) state.connection.disconnect(SERVER_FAILURE);
                }, timeoutMillis + CLEANUP_GRACE_MILLIS, TimeUnit.MILLISECONDS));
    }

    private void failServer(ServerState state) {
        if (!state.phase.compareAndSet(Phase.PREPARING, Phase.TERMINAL)) return;
        serverStates.remove(state.connection, state);
        state.cancelTimeout();
        if (isCurrent(state.listener, state.connection)) state.listener.disconnect(SERVER_FAILURE);
    }

    private void failServerWithoutMainThread(ServerState state) {
        if (!state.phase.compareAndSet(Phase.PREPARING, Phase.TERMINAL)) return;
        serverStates.remove(state.connection, state);
        state.cancelTimeout();
        if (isCurrent(state.listener, state.connection)) state.connection.disconnect(SERVER_FAILURE);
    }

    private void closeServer(ServerState state) {
        state.phase.set(Phase.TERMINAL);
        serverStates.remove(state.connection, state);
        state.cancelTimeout();
    }

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "Jane configuration timeout");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static boolean isCurrent(ICommonPacketListener listener, Connection connection) {
        try {
            return listener.isAcceptingMessages() && connection.isConnected()
                    && connection.getPacketListener() == listener;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private <T> void watch(CompletionStage<T> stage, BiConsumer<T, Throwable> completion) {
        CompletableFuture<T> bounded = new CompletableFuture<>();
        try {
            stage.whenComplete((value, failure) -> {
                if (failure == null) bounded.complete(value);
                else bounded.completeExceptionally(failure);
            });
        } catch (RuntimeException exception) {
            bounded.completeExceptionally(exception);
        }
        bounded.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenComplete(completion);
    }

    final class ManifestTask implements ICustomConfigurationTask {
        private final ServerConfigurationPacketListener listener;

        ManifestTask(ServerConfigurationPacketListener listener) {
            this.listener = listener;
        }

        @Override
        public void run(Consumer<CustomPacketPayload> sender) {
            Connection connection;
            try {
                connection = Objects.requireNonNull(listener.getConnection(), "connection");
            } catch (RuntimeException exception) {
                listener.disconnect(SERVER_FAILURE);
                return;
            }
            ServerState state = new ServerState(listener, connection);
            if (serverStates.putIfAbsent(connection, state) != null) {
                listener.disconnect(PROTOCOL_ERROR);
                return;
            }
            try {
                awaitServerCleanup(state);
            } catch (RuntimeException exception) {
                failServer(state);
                return;
            }
            CompletionStage<ManifestCodec.LoginOffer> stage;
            try {
                stage = Objects.requireNonNull(manifestSupplier.get(), "manifest stage");
            } catch (IOException | RuntimeException exception) {
                failServer(state);
                return;
            }
            watch(stage, (offer, failure) -> {
                try {
                    serverScheduler.accept(listener, () -> completeServer(state, sender, offer, failure));
                } catch (RuntimeException exception) {
                    // If the event loop rejects work, close the connection rather than strand its task.
                    failServerWithoutMainThread(state);
                }
            });
        }

        @Override
        public ConfigurationTask.Type type() {
            return TASK_TYPE;
        }
    }

    private static final class ServerState extends TimedState {
        private final ServerConfigurationPacketListener listener;
        private final Connection connection;

        private ServerState(ServerConfigurationPacketListener listener, Connection connection) {
            this.listener = listener;
            this.connection = connection;
        }
    }

    private static final class ClientState extends TimedState {
        private final ICommonPacketListener listener;
        private final Connection connection;

        private ClientState(ICommonPacketListener listener, Connection connection) {
            this.listener = listener;
            this.connection = connection;
        }
    }

    private static class TimedState {
        final AtomicReference<Phase> phase = new AtomicReference<>(Phase.PREPARING);
        private ScheduledFuture<?> timeout;

        final synchronized void setTimeout(ScheduledFuture<?> next) {
            if (timeout != null) timeout.cancel(false);
            timeout = null;
            if (phase.get() == Phase.TERMINAL) next.cancel(false);
            else timeout = next;
        }

        final synchronized void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
                timeout = null;
            }
        }
    }
}
