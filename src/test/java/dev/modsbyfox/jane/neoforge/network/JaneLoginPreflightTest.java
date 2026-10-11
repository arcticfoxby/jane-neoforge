package dev.modsbyfox.jane.neoforge.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.RequiredManifest;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class JaneLoginPreflightTest {
    private static final ManifestCodec.LoginOffer OFFER =
            new ManifestCodec.LoginOffer(new RequiredManifest(RequiredManifest.PROTOCOL, List.of()), null);

    @Test
    void queryPayloadRoundTripsAndRejectsMalformedFrames() throws IOException {
        byte[] manifest = ManifestCodec.encode(OFFER);
        JaneLoginQueryPayload original = new JaneLoginQueryPayload(manifest);
        FriendlyByteBuf encoded = buffer();
        original.write(encoded);
        JaneLoginQueryPayload decoded = JaneLoginQueryPayload.read(encoded);
        assertArrayEquals(manifest, decoded.bytes());
        assertEquals(OFFER, ManifestCodec.decodeOffer(decoded.bytes()));
        decoded.bytes()[0] = 99;
        assertArrayEquals(manifest, decoded.bytes());

        FriendlyByteBuf oversized = buffer();
        oversized.writeVarInt(JaneLoginQueryPayload.MAX_MANIFEST_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> JaneLoginQueryPayload.read(oversized));
        FriendlyByteBuf truncated = buffer();
        truncated.writeVarInt(manifest.length + 1).writeBytes(manifest);
        assertThrows(IllegalArgumentException.class, () -> JaneLoginQueryPayload.read(truncated));
        FriendlyByteBuf trailing = buffer();
        trailing.writeVarInt(manifest.length).writeBytes(manifest).writeByte(7);
        assertThrows(IllegalArgumentException.class, () -> JaneLoginQueryPayload.read(trailing));
        assertThrows(IllegalArgumentException.class, () -> new JaneLoginQueryPayload(new byte[7]));
    }

    @Test
    void answerPayloadRequiresExactNullableFlagAndKnownSingleByteStatus() {
        for (ResultPayload.Status status : ResultPayload.Status.values()) {
            FriendlyByteBuf encoded = buffer();
            encoded.writeByte(1);
            new JaneLoginAnswerPayload(status).write(encoded);
            assertEquals(status, JaneLoginAnswerPayload.readNullable(encoded).status());
        }
        FriendlyByteBuf absent = buffer();
        absent.writeByte(0);
        assertNull(JaneLoginAnswerPayload.readNullable(absent));

        for (byte[] malformed : List.of(new byte[0], new byte[] { 0, 0 },
                new byte[] { 1 }, new byte[] { 1, 0, 0 }, new byte[] { 1, 99 }, new byte[] { 2, 0 })) {
            FriendlyByteBuf encoded = buffer();
            encoded.writeBytes(malformed);
            assertThrows(IllegalArgumentException.class, () -> JaneLoginAnswerPayload.readNullable(encoded));
        }
    }

    @Test
    void waitsForManifestAndAnswerBeforeResumingExactlyOnce() {
        CompletableFuture<ManifestCodec.LoginOffer> prepared = new CompletableFuture<>();
        Queue<Runnable> main = new ArrayDeque<>();
        AtomicReference<JaneLoginQueryPayload> sent = new AtomicReference<>();
        AtomicInteger resumed = new AtomicInteger();
        List<Component> disconnects = new ArrayList<>();
        JaneLoginPreflight.Session session = new JaneLoginPreflight.Session(() -> prepared, main::add,
                () -> true, sent::set, resumed::incrementAndGet, disconnects::add, disconnects::add,
                System::nanoTime, Duration.ofSeconds(2), new Semaphore(32));
        session.begin();
        assertTrue(session.tickAndHold());
        assertNull(sent.get());
        prepared.complete(OFFER);
        assertNull(sent.get());
        main.remove().run();
        assertEquals(OFFER, decode(sent.get()));
        assertEquals(0, resumed.get());
        session.accept(JaneLoginQueryPayload.TRANSACTION_ID,
                new JaneLoginAnswerPayload(ResultPayload.Status.PASS));
        assertEquals(1, resumed.get());
        assertFalse(session.tickAndHold());
        assertTrue(disconnects.isEmpty());

        session.accept(JaneLoginQueryPayload.TRANSACTION_ID,
                new JaneLoginAnswerPayload(ResultPayload.Status.PASS));
        assertEquals(1, resumed.get());
        assertEquals(1, disconnects.size());
    }

    @Test
    void nullEarlyWrongAndNegativeAnswersFailClosed() {
        for (ResultPayload.Status status : List.of(ResultPayload.Status.ACTION_REQUIRED,
                ResultPayload.Status.PROTOCOL_ERROR)) {
            Probe probe = ready();
            probe.session.accept(JaneLoginQueryPayload.TRANSACTION_ID, new JaneLoginAnswerPayload(status));
            assertEquals(0, probe.resumed.get());
            assertEquals(1, probe.disconnects.size());
            assertTrue(probe.session.tickAndHold());
        }
        Probe missing = ready();
        missing.session.accept(JaneLoginQueryPayload.TRANSACTION_ID, null);
        assertEquals(1, missing.disconnects.size());
        assertTrue(missing.disconnects.get(0).getString().contains("required"));

        Probe wrongId = ready();
        wrongId.session.accept(1, new JaneLoginAnswerPayload(ResultPayload.Status.PASS));
        assertEquals(0, wrongId.resumed.get());
        assertEquals(1, wrongId.disconnects.size());

        CompletableFuture<ManifestCodec.LoginOffer> pending = new CompletableFuture<>();
        Probe early = new Probe(() -> pending, Runnable::run, System::nanoTime, Duration.ofSeconds(2));
        early.session.begin();
        early.session.accept(JaneLoginQueryPayload.TRANSACTION_ID,
                new JaneLoginAnswerPayload(ResultPayload.Status.PASS));
        assertEquals(0, early.resumed.get());
        assertEquals(1, early.disconnects.size());
        pending.complete(OFFER);
        assertNull(early.sent.get());
    }

    @Test
    void timeoutAndSchedulerFailureCannotResumeOrSendLate() {
        AtomicLong clock = new AtomicLong();
        CompletableFuture<ManifestCodec.LoginOffer> pending = new CompletableFuture<>();
        Probe timed = new Probe(() -> pending, Runnable::run, clock::get, Duration.ofNanos(10));
        timed.session.begin();
        clock.set(11);
        assertTrue(timed.session.tickAndHold());
        pending.complete(OFFER);
        assertNull(timed.sent.get());
        assertEquals(0, timed.resumed.get());
        assertEquals(1, timed.disconnects.size());
        assertEquals(32, timed.slots.availablePermits());

        AtomicLong answerClock = new AtomicLong();
        CompletableFuture<ManifestCodec.LoginOffer> nearlyLate = new CompletableFuture<>();
        Probe unanswered = new Probe(() -> nearlyLate, Runnable::run, answerClock::get,
                Duration.ofNanos(10));
        unanswered.session.begin();
        answerClock.set(9);
        nearlyLate.complete(OFFER);
        assertEquals(OFFER, decode(unanswered.sent.get()));
        answerClock.set(11);
        unanswered.session.accept(JaneLoginQueryPayload.TRANSACTION_ID,
                new JaneLoginAnswerPayload(ResultPayload.Status.PASS));
        assertEquals(0, unanswered.resumed.get());
        assertEquals(1, unanswered.disconnects.size());
        assertEquals(32, unanswered.slots.availablePermits());

        AtomicLong lateClock = new AtomicLong();
        CompletableFuture<ManifestCodec.LoginOffer> late = new CompletableFuture<>();
        Probe latePreparation = new Probe(() -> late, Runnable::run, lateClock::get, Duration.ofNanos(10));
        latePreparation.session.begin();
        lateClock.set(11);
        late.complete(OFFER);
        assertNull(latePreparation.sent.get());
        assertEquals(1, latePreparation.disconnects.size());
        assertEquals(32, latePreparation.slots.availablePermits());

        Probe rejected = new Probe(() -> CompletableFuture.completedFuture(OFFER), task -> {
            throw new IllegalStateException("server stopped");
        }, System::nanoTime, Duration.ofSeconds(2));
        rejected.session.begin();
        assertNull(rejected.sent.get());
        assertEquals(1, rejected.disconnects.size());
        assertEquals(0, rejected.resumed.get());
        assertEquals(32, rejected.slots.availablePermits());
    }

    @Test
    void globalAdmissionRejectsExcessAndReleasesExactlyOnce() {
        Semaphore slots = new Semaphore(1);
        CompletableFuture<ManifestCodec.LoginOffer> pending = new CompletableFuture<>();
        Probe first = new Probe(() -> pending, Runnable::run, System::nanoTime,
                Duration.ofSeconds(2), slots);
        first.session.begin();
        assertEquals(0, slots.availablePermits());

        Probe busy = new Probe(() -> CompletableFuture.completedFuture(OFFER), Runnable::run,
                System::nanoTime, Duration.ofSeconds(2), slots);
        busy.session.begin();
        assertNull(busy.sent.get());
        assertEquals(1, busy.disconnects.size());
        assertTrue(busy.disconnects.get(0).getString().contains("busy"));
        assertEquals(0, slots.availablePermits());

        pending.complete(OFFER);
        assertEquals(OFFER, decode(first.sent.get()));
        first.session.accept(JaneLoginQueryPayload.TRANSACTION_ID,
                new JaneLoginAnswerPayload(ResultPayload.Status.PASS));
        assertEquals(1, first.resumed.get());
        assertEquals(1, slots.availablePermits());
        first.session.accept(JaneLoginQueryPayload.TRANSACTION_ID,
                new JaneLoginAnswerPayload(ResultPayload.Status.PASS));
        assertEquals(1, slots.availablePermits());
        assertEquals(1, first.disconnects.size());
    }

    @Test
    void peerDisconnectReleasesPendingAdmissionWithoutAnotherTick() {
        Semaphore slots = new Semaphore(1);
        CompletableFuture<ManifestCodec.LoginOffer> pending = new CompletableFuture<>();
        Probe closed = new Probe(() -> pending, Runnable::run, System::nanoTime,
                Duration.ofSeconds(2), slots);
        closed.session.begin();
        assertEquals(0, slots.availablePermits());
        closed.session.connectionClosed();
        closed.session.connectionClosed();
        assertEquals(1, slots.availablePermits());
        pending.complete(OFFER);
        assertNull(closed.sent.get());
        assertEquals(0, closed.resumed.get());
    }

    @Test
    void userOverrideResumesButPreparationFailureDisconnects() {
        Probe approved = ready();
        approved.session.accept(JaneLoginQueryPayload.TRANSACTION_ID,
                new JaneLoginAnswerPayload(ResultPayload.Status.USER_OVERRIDE));
        assertEquals(1, approved.resumed.get());
        assertTrue(approved.disconnects.isEmpty());

        Probe failed = new Probe(() -> {
            throw new IOException("manifest scan failed");
        }, Runnable::run, System::nanoTime, Duration.ofSeconds(2));
        failed.session.begin();
        assertEquals(1, failed.disconnects.size());
        assertNull(failed.sent.get());
    }

    private static Probe ready() {
        Probe probe = new Probe(() -> CompletableFuture.completedFuture(OFFER), Runnable::run,
                System::nanoTime, Duration.ofSeconds(2));
        probe.session.begin();
        assertEquals(OFFER, decode(probe.sent.get()));
        return probe;
    }

    private static ManifestCodec.LoginOffer decode(JaneLoginQueryPayload payload) {
        try {
            return ManifestCodec.decodeOffer(payload.bytes());
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static final class Probe {
        private final AtomicReference<JaneLoginQueryPayload> sent = new AtomicReference<>();
        private final AtomicInteger resumed = new AtomicInteger();
        private final List<Component> disconnects = new ArrayList<>();
        private final Semaphore slots;
        private final JaneLoginPreflight.Session session;

        private Probe(JaneLoginPreflight.ManifestSupplier supplier, java.util.concurrent.Executor scheduler,
                java.util.function.LongSupplier clock, Duration timeout) {
            this(supplier, scheduler, clock, timeout, new Semaphore(32));
        }

        private Probe(JaneLoginPreflight.ManifestSupplier supplier, java.util.concurrent.Executor scheduler,
                java.util.function.LongSupplier clock, Duration timeout, Semaphore slots) {
            this.slots = slots;
            session = new JaneLoginPreflight.Session(supplier, scheduler, () -> true, sent::set,
                    resumed::incrementAndGet, disconnects::add, disconnects::add, clock, timeout, slots);
        }
    }
}
