package dev.modsbyfox.jane.neoforge.network;

import dev.modsbyfox.jane.core.ManifestCodec;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Holds a login connection after its configured authentication and canPlayerLogin admission flow. */
public final class JaneLoginPreflight {
    @FunctionalInterface
    public interface ManifestSupplier {
        CompletionStage<ManifestCodec.LoginOffer> get() throws IOException;
    }

    private enum Phase { NEW, PREPARING, AWAITING_ANSWER, COMPLETE, TERMINAL }

    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final Semaphore PENDING_LOGINS = new Semaphore(32);
    private static final Component MISSING_CLIENT = Component.literal("Jane is required on this client to join this server.");
    private static final Component SERVER_FAILURE = Component.literal("Jane could not prepare the required mod manifest.");
    private static final Component SERVER_BUSY = Component.literal("Jane login preflight is busy; try again shortly.");
    private static final Component ACTION_REQUIRED = Component.literal("Jane requires mod changes before joining. Apply them, restart Minecraft, and reconnect.");
    private static final Component PROTOCOL_ERROR = Component.literal("Jane login preflight failed or timed out.");
    private static volatile ManifestSupplier registeredSupplier;

    private JaneLoginPreflight() { }

    public static void register(ManifestSupplier supplier) {
        registeredSupplier = Objects.requireNonNull(supplier, "supplier");
    }

    public static Session newSession(Executor mainThread, BooleanSupplier current,
            Consumer<JaneLoginQueryPayload> send, Runnable resume, Consumer<Component> disconnect,
            Consumer<Component> emergencyClose) {
        return new Session(registeredSupplier, mainThread, current, send, resume, disconnect, emergencyClose,
                System::nanoTime, TIMEOUT, PENDING_LOGINS);
    }

    /** One session belongs to the original login listener and its connection. */
    public static final class Session {
        private final ManifestSupplier supplier;
        private final Executor mainThread;
        private final BooleanSupplier current;
        private final Consumer<JaneLoginQueryPayload> send;
        private final Runnable resume;
        private final Consumer<Component> disconnect;
        private final Consumer<Component> emergencyClose;
        private final LongSupplier clock;
        private final long timeoutNanos;
        private final Semaphore pendingLogins;
        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.NEW);
        private final AtomicBoolean admitted = new AtomicBoolean();
        private long startedAt;

        Session(ManifestSupplier supplier, Executor mainThread, BooleanSupplier current,
                Consumer<JaneLoginQueryPayload> send, Runnable resume, Consumer<Component> disconnect,
                Consumer<Component> emergencyClose, LongSupplier clock, Duration timeout,
                Semaphore pendingLogins) {
            this.supplier = supplier;
            this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
            this.current = Objects.requireNonNull(current, "current");
            this.send = Objects.requireNonNull(send, "send");
            this.resume = Objects.requireNonNull(resume, "resume");
            this.disconnect = Objects.requireNonNull(disconnect, "disconnect");
            this.emergencyClose = Objects.requireNonNull(emergencyClose, "emergencyClose");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.pendingLogins = Objects.requireNonNull(pendingLogins, "pendingLogins");
            timeoutNanos = Objects.requireNonNull(timeout, "timeout").toNanos();
            if (timeoutNanos <= 0) throw new IllegalArgumentException("timeout must be positive");
        }

        public void begin() {
            if (!phase.compareAndSet(Phase.NEW, Phase.PREPARING)) {
                fail(PROTOCOL_ERROR);
                return;
            }
            if (!pendingLogins.tryAcquire()) {
                fail(SERVER_BUSY);
                return;
            }
            admitted.set(true);
            if (phase.get() != Phase.PREPARING) {
                releaseAdmission();
                return;
            }
            startedAt = clock.getAsLong();
            if (supplier == null) {
                fail(SERVER_FAILURE);
                return;
            }
            CompletionStage<ManifestCodec.LoginOffer> stage;
            try {
                stage = Objects.requireNonNull(supplier.get(), "manifest stage");
                stage.whenComplete((offer, failure) -> {
                    try {
                        mainThread.execute(() -> prepared(offer, failure));
                    } catch (RuntimeException exception) {
                        closeWithoutMainThread();
                    }
                });
            } catch (IOException | RuntimeException exception) {
                fail(SERVER_FAILURE);
            }
        }

        /** Suppress vanilla's 600-tick login timeout while this bounded preflight is pending. */
        public boolean tickAndHold() {
            Phase now = phase.get();
            if (now == Phase.PREPARING || now == Phase.AWAITING_ANSWER) {
                if (clock.getAsLong() - startedAt >= timeoutNanos) fail(PROTOCOL_ERROR);
                return true;
            }
            return now == Phase.TERMINAL;
        }

        /** A peer may close while the server is waiting, without another login tick. */
        public void connectionClosed() {
            phase.set(Phase.TERMINAL);
            releaseAdmission();
        }

        public void accept(int transactionId, CustomQueryAnswerPayload payload) {
            if (transactionId != JaneLoginQueryPayload.TRANSACTION_ID || phase.get() != Phase.AWAITING_ANSWER) {
                fail(PROTOCOL_ERROR);
                return;
            }
            if (clock.getAsLong() - startedAt >= timeoutNanos) {
                fail(PROTOCOL_ERROR);
                return;
            }
            if (payload == null) {
                fail(MISSING_CLIENT);
                return;
            }
            if (!(payload instanceof JaneLoginAnswerPayload answer)) {
                fail(PROTOCOL_ERROR);
                return;
            }
            if (!isCurrent()) {
                fail(PROTOCOL_ERROR);
                return;
            }
            switch (answer.status()) {
                case PASS, USER_OVERRIDE -> {
                    if (!phase.compareAndSet(Phase.AWAITING_ANSWER, Phase.COMPLETE)) {
                        fail(PROTOCOL_ERROR);
                        return;
                    }
                    releaseAdmission();
                    if (answer.status() == ResultPayload.Status.USER_OVERRIDE)
                        LOGGER.warn("Jane login preflight accepted USER_OVERRIDE; the required baseline is unsatisfied");
                    try {
                        resume.run();
                    } catch (RuntimeException exception) {
                        fail(PROTOCOL_ERROR);
                    }
                }
                case ACTION_REQUIRED -> fail(ACTION_REQUIRED);
                case PROTOCOL_ERROR -> fail(PROTOCOL_ERROR);
            }
        }

        private void prepared(ManifestCodec.LoginOffer offer, Throwable failure) {
            if (phase.get() != Phase.PREPARING) return;
            if (clock.getAsLong() - startedAt >= timeoutNanos) {
                fail(PROTOCOL_ERROR);
                return;
            }
            if (!isCurrent()) {
                fail(PROTOCOL_ERROR);
                return;
            }
            if (failure != null || offer == null) {
                fail(SERVER_FAILURE);
                return;
            }
            try {
                JaneLoginQueryPayload payload = new JaneLoginQueryPayload(ManifestCodec.encode(offer));
                if (!phase.compareAndSet(Phase.PREPARING, Phase.AWAITING_ANSWER)) return;
                send.accept(payload);
            } catch (IOException | RuntimeException exception) {
                fail(SERVER_FAILURE);
            }
        }

        private boolean isCurrent() {
            try {
                return current.getAsBoolean();
            } catch (RuntimeException exception) {
                return false;
            }
        }

        private void fail(Component reason) {
            if (phase.getAndSet(Phase.TERMINAL) == Phase.TERMINAL) return;
            releaseAdmission();
            try {
                disconnect.accept(reason);
            } catch (RuntimeException exception) {
                emergencyClose.accept(reason);
            }
        }

        private void closeWithoutMainThread() {
            if (phase.getAndSet(Phase.TERMINAL) == Phase.TERMINAL) return;
            releaseAdmission();
            emergencyClose.accept(SERVER_FAILURE);
        }

        private void releaseAdmission() {
            if (admitted.compareAndSet(true, false)) pendingLogins.release();
        }
    }
}
