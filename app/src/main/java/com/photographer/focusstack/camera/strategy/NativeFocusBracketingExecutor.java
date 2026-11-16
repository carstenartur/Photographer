package com.photographer.focusstack.camera.strategy;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraCapabilityProvider;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import com.photographer.focusstack.common.camera.CapturedImageFormat;
import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionProvider;
import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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

        LinkedHashMap<String, ObservedShot> observed = new LinkedHashMap<>();
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
            while (true) {
                checkCancellation(cancellationRequested);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    ingestReady(mediaProvider, observed.values(), failures, ingestor,
                            cancellationRequested, true);
                    if (observed.size() < request.numberOfShots()) {
                        state = observed.isEmpty() ? TIMED_OUT : PARTIAL;
                        failures.add("Timed out after observing " + observed.size()
                                + " of " + request.numberOfShots() + " expected shots");
                    }
                    break;
                }

                Duration slice = observationSlice(remaining, observed.values());
                mediaProvider.awaitAddedMedia(eventMode, slice)
                        .ifPresent(reference -> addObserved(
                                observed, before, reference, request.numberOfShots()));

                for (CameraMediaReference reference : snapshot(mediaProvider)) {
                    addObserved(observed, before, reference, request.numberOfShots());
                }

                ingestReady(mediaProvider, observed.values(), failures, ingestor,
                        cancellationRequested, false);
                if (observed.size() == request.numberOfShots()
                        && observed.values().stream().allMatch(shot -> shot.finished)) {
                    break;
                }
            }

            if (state == FAILED) {
                if (observed.size() == request.numberOfShots()
                        && observed.values().stream().allMatch(shot -> shot.frameId != null)
                        && failures.isEmpty()) {
                    state = COMPLETE;
                } else if (!observed.isEmpty()) {
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
                observed.values().stream().map(ObservedShot::bestReference).toList(),
                observed.values().stream().map(shot -> shot.frameId)
                        .filter(Objects::nonNull).toList(),
                state,
                failures.isEmpty() ? null : String.join("; ", failures),
                restorationComplete);
    }

    private static void addObserved(
            Map<String, ObservedShot> observed,
            Set<String> before,
            CameraMediaReference reference,
            int maximum) {
        String mediaPath = mediaIdentity(reference);
        if (before.contains(mediaPath)) {
            return;
        }
        String shotKey = CapturedImageFormat.shotKey(mediaPath);
        ObservedShot shot = observed.get(shotKey);
        if (shot == null && observed.size() < maximum) {
            shot = new ObservedShot(reference);
            observed.put(shotKey, shot);
        }
        if (shot != null && !shot.finished) {
            shot.references.putIfAbsent(mediaPath, reference);
        }
    }

    private static Duration observationSlice(long remaining, Iterable<ObservedShot> shots) {
        long wait = Math.min(remaining, OBSERVATION_SLICE.toNanos());
        long now = System.nanoTime();
        for (ObservedShot shot : shots) {
            if (!shot.finished) {
                wait = Math.min(wait, Math.max(1L, shot.preferRawUntil - now));
            }
        }
        return Duration.ofNanos(wait);
    }

    private static void ingestReady(
            CameraMediaProvider mediaProvider,
            Iterable<ObservedShot> observed,
            List<String> failures,
            MediaIngestor ingestor,
            BooleanSupplier cancellationRequested,
            boolean timedOut) {
        for (ObservedShot shot : observed) {
            checkCancellation(cancellationRequested);
            if (shot.finished) {
                continue;
            }
            List<CameraMediaReference> ordered = shot.orderedReferences();
            boolean waitingForPair = !timedOut && System.nanoTime() < shot.preferRawUntil
                    && ordered.size() == 1;
            for (CameraMediaReference reference : ordered) {
                if (waitingForPair && !CapturedImageFormat.isCr3(reference.fileName())
                        || !shot.attempted.add(mediaIdentity(reference))) {
                    continue;
                }
                try {
                    String id = Objects.requireNonNull(
                            ingestor.ingest(mediaProvider, reference), "ingested frame id");
                    if (id.isBlank()) {
                        throw new IllegalStateException("ingested frame id is blank");
                    }
                    shot.selected = reference;
                    shot.frameId = id;
                    shot.finished = true;
                    break;
                } catch (RuntimeException exception) {
                    if (exception instanceof CameraOperationException cameraException
                            && cameraException.reason() == CameraOperationException.Reason.CANCELLED) {
                        throw cameraException;
                    }
                    checkCancellation(cancellationRequested);
                    shot.lastFailure = "Could not ingest " + reference.fileName() + ": "
                            + safeFailure(exception);
                }
            }
            if (!shot.finished && !waitingForPair) {
                shot.finished = true;
                failures.add(shot.lastFailure);
            }
        }
    }

    private static String mediaIdentity(CameraMediaReference reference) {
        String path = reference.relativePath();
        int contents = path.indexOf("/contents/");
        return contents < 0 ? path : path.substring(contents);
    }

    private static final class ObservedShot {
        private final Map<String, CameraMediaReference> references = new LinkedHashMap<>();
        private final Set<String> attempted = new LinkedHashSet<>();
        private final long preferRawUntil;
        private CameraMediaReference selected;
        private String frameId;
        private String lastFailure;
        private boolean finished;

        private ObservedShot(CameraMediaReference reference) {
            preferRawUntil = CapturedImageFormat.canHavePairedFile(reference.fileName())
                    ? deadline(CapturedImageFormat.PAIRED_FILE_WAIT) : System.nanoTime();
        }

        private List<CameraMediaReference> orderedReferences() {
            return references.values().stream()
                    .sorted(Comparator.comparingInt((CameraMediaReference reference) ->
                            CapturedImageFormat.processingPriority(reference.fileName())).reversed())
                    .toList();
        }

        private CameraMediaReference bestReference() {
            return selected == null ? orderedReferences().getFirst() : selected;
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
            result.add(mediaIdentity(reference));
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
