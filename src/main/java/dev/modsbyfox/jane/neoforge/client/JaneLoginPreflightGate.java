package dev.modsbyfox.jane.neoforge.client;

import java.util.concurrent.atomic.AtomicInteger;

/** Exactly one login query may produce a normal answer; a duplicate makes the exchange terminal. */
final class JaneLoginPreflightGate {
    private static final int NEW = 0;
    private static final int ASSESSING = 1;
    private static final int ANSWERED = 2;
    private static final int FAILED = 3;

    private final AtomicInteger state = new AtomicInteger(NEW);

    boolean begin() {
        return state.compareAndSet(NEW, ASSESSING);
    }

    boolean finish() {
        return state.compareAndSet(ASSESSING, ANSWERED);
    }

    boolean fail() {
        return state.getAndSet(FAILED) != FAILED;
    }
}
