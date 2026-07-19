package com.photographer.focusstack.camera.strategy;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraCapabilityProvider;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionProvider;
import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult.CompletionState.CANCELLED;
import static com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult.CompletionState.COMPLETE;
import static com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult.CompletionState.FAILED;
import static com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult.CompletionState.PARTIAL;
import static com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult.CompletionState.TIMED_OUT;

/**
 * Vendor-neutral orchestration for one prepared, camera-owned focus-bracketing sequence.
 *
 * <p>The executor triggers exactly once, treats events as wake-up hints rather than
 * one-frame notifications, refreshes the bounded media snapshot after every hint and
 * preserves every successfully ingested partial frame.</p>
 */
public final class NativeFocusBracketingExecutor {

    private static final CameraMediaProvider.MediaQuery SNAPSHOT_QUERY =
            new CameraMediaProvider.MediaQuery(1_000, 100, Set.of());
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration OBSERVATION_SLICE = Duration.ofSeconds(2);

    /** Persists one complete media object and returns its application frame id. */
    @FunctionalInterface
    public interface MediaIngestor {
        String ingest(CameraMediaProvider mediaProvider, CameraMediaReference mediaReference);
    }

    public NativeFocusBracketingExecutionResult execute(
            CameraBackend backend,
            NativeFocusBracketingRequest request,
            Duration timeout,
            BooleanSupplier cancellationRequested,
            MediaIngestor ingestor) {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        Objects.requireNonNull(ingestor, "ingestor");
        validateTimeout(timeout);

        if (!(backend instanceof NativeFocusBracketingExecutionProvider nativeProvider)) {
            throw new IllegalArgumentException(
                    "camera backend does not implement native focus-bracketing execution");
        }
        if (!(backend instanceof CameraMediaProvider mediaProvider)) {
            throw new IllegalArgumentException(
                    "camera backend does not implement media observation and transfer");
        }
        nativeProvider.getNativeFocusBracketingCapabilities().requireExecutable(request);

        LinkedHashMap<String, CameraMediaReference> observed = new LinkedHashMap<>();
        LinkedHashSet<String> ingestionAttempted = new LinkedHashSet<>();
        List<String> ingestedFrameIds = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        NativeFocusBracketingExecutionResult.CompletionState state = FAILED;
        boolean restorationComplete = false;
        boolean triggered = false;

        NativeFocusBracketingSession session = nativeProvider.prepareNativeFocusBracketing(request);
        try {
            Set<String> before = snapshotPaths(mediaProvider);
            checkCancellation(cancellationRequested);

            nativeProvider.triggerNativeFocusBracketing(session);
            triggered = true;

            CameraMediaProvider.EventMode eventMode = selectEventMode(backend);
            long deadline = deadline(timeout);
            while (observed.size() < request.numberOfShots()) {
                checkCancellation(cancellationRequested);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    state = observed.isEmpty() ? TIMED_OUT : PARTIAL;
                    failures.add("Timed out after observing " + observed.size()
                            + " of " + request.numberOfShots() + " expected media files");
                    break;
                }

                Duration slice = Duration.ofNanos(Math.min(
                        remaining, OBSERVATION_SLICE.toNanos()));
                mediaProvider.awaitAddedMedia(eventMode, slice)
                        .ifPresent(reference -> addObserved(
                                observed, before, reference, request.numberOfShots()));

                for (CameraMediaReference reference : snapshot(mediaProvider)) {
                    addObserved(observed, before, reference, request.numberOfShots());
                    if (observed.size() >= request.numberOfShots()) {
                        break;
                    }
                }

                ingestNew(
                        mediaProvider,
                        observed.values(),
                        ingestionAttempted,
                        ingestedFrameIds,
                        failures,
                        ingestor);
            }

            if (state == FAILED) {
                if (observed.size() == request.numberOfShots()
                        && ingestedFrameIds.size() == request.numberOfShots()
                        && failures.isEmpty()) {
                    state = COMPLETE;
                } else if (!observed.isEmpty() || !ingestedFrameIds.isEmpty()) {
                    state = PARTIAL;
                    if (failures.isEmpty()) {
                        failures.add("Observed or ingested fewer frames than requested");
                    }
                } else {
                    failures.add("No native sequence media was observed");
                }
            }
        } catch (CameraOperationException exception) {
            if (exception.reason() == CameraOperationException.Reason.CANCELLED) {
                state = CANCELLED;
                failures.add("Native sequence observation was cancelled");
            } else {
                state = observed.isEmpty() ? FAILED : PARTIAL;
                failures.add(safeFailure(exception));
            }
        } catch (CancellationRequestedException exception) {
            state = CANCELLED;
            failures.add("Native sequence execution was cancelled");
        } catch (RuntimeException exception) {
            state = observed.isEmpty() ? FAILED : PARTIAL;
            failures.add(safeFailure(exception));
        } finally {
            try {
                session.close();
                restorationComplete = true;
            } catch (RuntimeException restorationFailure) {
                restorationComplete = false;
                state = FAILED;
                failures.add("Camera settings restoration failed: "
                        + safeFailure(restorationFailure));
            }
        }

        if (state == COMPLETE && !restorationComplete) {
            state = FAILED;
        }
        if (!triggered && state != CANCELLED) {
            state = FAILED;
            failures.add("Native shutter sequence was not triggered");
        }

        return new NativeFocusBracketingExecutionResult(
                request.numberOfShots(),
                List.copyOf(observed.values()),
                List.copyOf(ingestedFrameIds),
                state,
                failures.isEmpty() ? null : String.join("; ", failures),
                restorationComplete);
    }

    private static void addObserved(
            Map<String, CameraMediaReference> observed,
            Set<String> before,
            CameraMediaReference reference,
            int maximum) {
        if (observed.size() >= maximum
                || before.contains(reference.relativePath())) {
            return;
        }
        observed.putIfAbsent(reference.relativePath(), reference);
    }

    private static void ingestNew(
            CameraMediaProvider mediaProvider,
            Iterable<CameraMediaReference> observed,
            Set<String> attemptedPaths,
            List<String> frameIds,
            List<String> failures,
            MediaIngestor ingestor) {
        for (CameraMediaReference reference : observed) {
            if (!attemptedPaths.add(reference.relativePath())) {
                continue;
            }
            try {
                String frameId = Objects.requireNonNull(
                        ingestor.ingest(mediaProvider, reference), "ingested frame id");
                if (frameId.isBlank()) {
                    throw new IllegalStateException("ingested frame id is blank");
                }
                frameIds.add(frameId);
            } catch (RuntimeException exception) {
                failures.add("Could not ingest " + reference.fileName() + ": "
                        + safeFailure(exception));
            }
        }
    }

    private static CameraMediaProvider.EventMode selectEventMode(CameraBackend backend) {
        if (backend instanceof CameraCapabilityProvider capabilityProvider) {
            CameraCapabilitySnapshot snapshot = capabilityProvider.getCapabilitySnapshot();
            if (available(snapshot, "event-monitoring")) {
                return CameraMediaProvider.EventMode.MONITORING;
            }
            if (available(snapshot, "event-polling-continuation")) {
                return CameraMediaProvider.EventMode.POLLING_CONTINUATION;
            }
        }
        return CameraMediaProvider.EventMode.POLLING;
    }

    private static boolean available(CameraCapabilitySnapshot snapshot, String id) {
        return snapshot.capabilities().stream()
                .anyMatch(capability -> capability.id().equals(id)
                        && capability.availability()
                        == CameraCapabilitySnapshot.Availability.AVAILABLE);
    }

    private static Set<String> snapshotPaths(CameraMediaProvider mediaProvider) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (CameraMediaReference reference : snapshot(mediaProvider)) {
            result.add(reference.relativePath());
        }
        return Set.copyOf(result);
    }

    private static List<CameraMediaReference> snapshot(CameraMediaProvider mediaProvider) {
        return mediaProvider.listMedia(SNAPSHOT_QUERY).items();
    }

    private static void checkCancellation(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted() || cancellationRequested.getAsBoolean()) {
            throw new CancellationRequestedException();
        }
    }

    private static long deadline(Duration timeout) {
        long now = System.nanoTime();
        long nanos = timeout.toNanos();
        return now > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : now + nanos;
    }

    private static void validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "timeout must be positive and no longer than " + MAX_TIMEOUT);
        }
    }

    private static String safeFailure(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private static final class CancellationRequestedException extends RuntimeException {
    }
}
