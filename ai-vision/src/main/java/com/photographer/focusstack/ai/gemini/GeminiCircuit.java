package com.photographer.focusstack.ai.gemini;

import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.ai.VisionResult;

import java.time.Duration;
import java.util.function.LongSupplier;

/** One bounded concurrency budget and circuit per configured client, shared by all operations. */
final class GeminiCircuit {
    private static final int MAX_IN_FLIGHT = 4;
    private static final int FAILURES_TO_OPEN = 3;
    private static final long COOLDOWN = Duration.ofSeconds(30).toNanos();

    record Admission(long generation, VisionFailureKind failure, Integer retryAfterSeconds) {
        boolean allowed() {
            return failure == VisionFailureKind.NONE;
        }
    }

    private final LongSupplier nanoTime;
    private int inFlight;
    private int consecutiveFailures;
    private long generation;
    private long retryAt;
    private boolean open;
    private boolean probing;

    GeminiCircuit(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    synchronized Admission admit() {
        long remaining = retryAt - nanoTime.getAsLong();
        if (open && (remaining > 0 || probing)) {
            int seconds = remaining > 0 ? (int) Math.ceil(remaining / 1_000_000_000d) : 1;
            return new Admission(generation, VisionFailureKind.CIRCUIT_OPEN, seconds);
        }
        if (inFlight == MAX_IN_FLIGHT) {
            return new Admission(generation, VisionFailureKind.RATE_LIMITED, 1);
        }
        inFlight++;
        probing = open;
        return new Admission(generation, VisionFailureKind.NONE, null);
    }

    synchronized void complete(Admission admission, VisionResult result) {
        inFlight--;
        if (admission.generation() != generation) {
            return; // A late response from before the outage cannot close the circuit.
        }
        boolean transientFailure = result != null && result.retryable();
        if (transientFailure && (++consecutiveFailures >= FAILURES_TO_OPEN || probing)) {
            int retryAfter = result.retryAfterSeconds() == null ? 0 : result.retryAfterSeconds();
            retryAt = nanoTime.getAsLong() + Math.max(COOLDOWN, Duration.ofSeconds(retryAfter).toNanos());
            open = true;
            probing = false;
            generation++;
        } else if (!transientFailure) {
            consecutiveFailures = 0;
            open = false;
            probing = false;
        }
    }
}
