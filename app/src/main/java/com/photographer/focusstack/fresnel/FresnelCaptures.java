package com.photographer.focusstack.fresnel;

import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.camera.service.FrameDataStore;
import com.photographer.focusstack.camera.session.CameraLeaseIdentityProvider;
import com.photographer.focusstack.camera.session.CameraLeaseRequest;
import com.photographer.focusstack.camera.session.CameraSessionConflictException;
import com.photographer.focusstack.camera.session.CameraSessionLease;
import com.photographer.focusstack.camera.session.CameraSessionLeaseManager;
import com.photographer.focusstack.camera.session.CameraWorkflowType;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CameraProfile;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.photographer.focusstack.fresnel.CaptureSession.State;
import static com.photographer.focusstack.fresnel.CaptureSession.StepState;

/** Bounded Photographer-owned still captures. The initial adapter exposes PNG mock profiles. */
@Service
@ConditionalOnProperty(name = "focusstack.fresnel.enabled", havingValue = "true")
public final class FresnelCaptures implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FresnelCaptures.class);
    static final List<String> CAPABILITIES = List.of("STILL_CAPTURE", "EXTERNAL_STEP_TRIGGER",
            "DURABLE_MEDIA", "RANGE_DOWNLOAD", "SHA256_INTEGRITY");
    record OwnedCamera(CameraSessionLease lease, CameraBackend backend) {}
    record TriggerResult(int status, Object body) {}
    record Media(CaptureSession.Asset metadata, Path path) {}

    private final CameraProfileService profiles;
    private final CameraBackendFactory backends;
    private final CameraLeaseIdentityProvider identities;
    private final CameraSessionLeaseManager leases;
    private final FresnelPairings pairings;
    private final FrameDataStore media;
    private final CaptureJournal journal;
    private final MeterRegistry metrics;
    private final Map<String,CaptureSession> sessions = new LinkedHashMap<>();
    private final Map<String,OwnedCamera> active = new HashMap<>();
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().daemon().name("fresnel-capture-", 0).factory());
    private static final int MAX_RETAINED_SESSIONS = 512;
    private final Duration retention;
    private Instant nextPurge = Instant.EPOCH;
    private boolean unavailable;

    @Autowired
    public FresnelCaptures(CameraProfileService profiles, CameraBackendFactory backends,
                          CameraLeaseIdentityProvider identities, CameraSessionLeaseManager leases,
                          FresnelPairings pairings, FrameDataStore media, MeterRegistry metrics,
                          @Value("${focusstack.fresnel.directory:${focusstack.storage.images-dir:./data/images}/fresnel}") Path directory,
                          @Value("${focusstack.fresnel.retention:PT0S}") Duration retention)
            throws IOException {
        if (retention.isNegative() || (!retention.isZero() && retention.compareTo(Duration.ofDays(1)) < 0))
            throw new IllegalArgumentException("Fresnel retention must be disabled or at least one day");
        this.retention = retention;
        this.profiles = profiles;
        this.backends = backends;
        this.identities = identities;
        this.leases = leases;
        this.pairings = pairings;
        this.media = media;
        this.metrics = metrics;
        this.journal = new CaptureJournal(directory.resolve("sessions"));
        try {
            for (CaptureSession stored : journal.load()) {
                if (stored.purging) {
                    removeExpired(stored);
                } else {
                    recover(stored);
                    sessions.put(stored.id, stored);
                }
            }
        } catch (RuntimeException | IOException e) {
            journal.close();
            throw e;
        }
        workers.scheduleWithFixedDelay(() -> expire(Instant.now()), 1, 1, TimeUnit.SECONDS);
    }

    public Map<String,Object> descriptor() {
        String version = getClass().getPackage().getImplementationVersion();
        return Map.of("protocolVersion", 1, "minimumSupportedProtocolVersion", 1, "instanceId", journal.instanceId,
                "displayName", "Photographer", "applicationVersion", version == null ? "development" : version,
                "capabilities", CAPABILITIES, "supportedFormats", List.of("PNG"), "eventTransports", List.of("POLLING"),
                "limits", Map.of("maximumSteps", FresnelPlan.MAX_STEPS, "maximumAssetBytes", FresnelPlan.MAX_ASSET_BYTES,
                        "maximumSessionDurationMs", FresnelPlan.MAX_DURATION_MS, "maximumTriggerWaitMs", FresnelPlan.MAX_WAIT_MS,
                        "idempotencyRetentionSeconds", 86400), "serverTime", Instant.now().toString());
    }

    synchronized List<Map<String,Object>> cameras() {
        ready();
        return profiles.getAllProfiles().stream().filter(FresnelCaptures::supported).map(profile -> {
            boolean busy = identities.resolve(profile).map(leases::isLeased).orElse(false);
            return Map.<String,Object>of("cameraProfileId", alias(profile), "displayName", "Mock camera",
                    "state", busy ? "BUSY" : "CONNECTED", "capabilities", CAPABILITIES, "supportedFormats", List.of("PNG"));
        }).toList();
    }

    private static boolean supported(CameraProfile profile) { return "mock".equalsIgnoreCase(profile.backendType()); }

    static String alias(CameraProfile profile) {
        return "camera-" + FresnelPlan.hash(profile.id().getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    public synchronized List<Map<String,Object>> activeSessions(String pairingId) {
        ready();
        return sessions.values().stream().filter(s -> s.pairingId.equals(pairingId) && !s.state.terminal())
                .map(CaptureSession::active).toList();
    }

    synchronized Map<String,Object> create(String pairingId, String key, byte[] body) {
        ready();
        FresnelPlan.requireId(key);
        String hash = FresnelPlan.hash(body);
        for (var stored : sessions.values()) {
            if (!stored.purging && stored.pairingId.equals(pairingId) && stored.createKey.equals(key)) {
                if (!stored.bodyHash.equals(hash)) throw new FresnelProblem(409, "IDEMPOTENCY_CONFLICT");
                recordReplay("create", "accepted");
                return stored.created();
            }
        }
        FresnelPlan plan = FresnelPlan.parse(body, FresnelPlan.class);
        plan.validate();
        var profile = profiles.getAllProfiles().stream().filter(FresnelCaptures::supported)
                .filter(p -> alias(p).equals(plan.cameraProfileId())).findFirst()
                .orElseThrow(() -> new FresnelProblem(404, "CAMERA_NOT_FOUND"));
        var settings = plan.requestedSettings();
        if (settings.lockExposure() || settings.lockFocus() || !"PNG".equals(settings.preferredFormat()))
            throw new FresnelProblem(422, "UNSUPPORTED_SETTINGS");
        if (sessions.size() >= MAX_RETAINED_SESSIONS) purge(Instant.now());
        if (sessions.size() >= MAX_RETAINED_SESSIONS || active.size() >= 16) throw new FresnelProblem(429, "CAPTURE_LIMIT");
        var session = CaptureSession.create(pairingId, key, hash, profile.id(), plan);
        var keyForCamera = identities.resolve(profile).orElseThrow(() -> new FresnelProblem(422, "UNSUPPORTED_SETTINGS"));
        CameraSessionLease lease;
        try {
            lease = leases.acquire(new CameraLeaseRequest(keyForCamera, CameraWorkflowType.FRESNEL_CAPTURE, session.id,
                    session.id, Duration.ofMillis(FresnelPlan.MAX_DURATION_MS)));
        } catch (CameraSessionConflictException conflict) {
            throw new FresnelProblem(409, "LEASE_CONFLICT");
        }
        try {
            var camera = new OwnedCamera(lease, backends.getBackend(profile));
            save(session);
            sessions.put(session.id, session);
            active.put(session.id, camera);
            recordSessionState(session);
            return session.created();
        } catch (RuntimeException failed) {
            lease.close();
            throw failed;
        }
    }

    private CaptureSession owned(String pairing, String sessionId) {
        ready();
        var session = sessions.get(sessionId);
        if (session == null || session.purging || !session.pairingId.equals(pairing)) throw new FresnelProblem(404, "SESSION_NOT_FOUND");
        return session;
    }

    synchronized Map<String,Object> status(String pairing, String sessionId) { return owned(pairing, sessionId).status(); }

    synchronized Map<String,Object> events(String pairing, String id, long cursor, int limit) {
        var session = owned(pairing, id);
        if (cursor < 0 || limit < 1 || limit > 1000) throw new FresnelProblem(400, "INVALID_REQUEST");
        var page = session.events.stream().filter(e -> e.eventId() > cursor).limit(limit).toList();
        long end = page.isEmpty() ? cursor : page.getLast().eventId();
        return Map.of("events", page, "lastEventId", end, "hasMore", end < session.events.size());
    }

    synchronized TriggerResult trigger(String pairing, String sessionId, String stepId, String key, byte[] body) {
        var session = owned(pairing, sessionId);
        FresnelPlan.requireId(key);
        String hash = FresnelPlan.hash(body);
        for (var old : session.steps) {
            if (key.equals(old.key)) {
                if (!old.stepId.equals(stepId) || !old.bodyHash.equals(hash)) throw new FresnelProblem(409, "IDEMPOTENCY_CONFLICT");
                if (old.result != null && old.verified && verifiedFrame(session, old) != null) {
                    recordReplay("trigger", "completed");
                    return new TriggerResult(200, old.result);
                }
                if (old.state == StepState.CAPTURING && session.state == State.CAPTURING) {
                    recordReplay("trigger", "pending");
                    return new TriggerResult(202, session.status());
                }
            }
        }
        var step = session.step(stepId);
        if (session.state == State.RECOVERY_REQUIRED || step.state == StepState.RECOVERY_REQUIRED)
            throw new FresnelProblem(409, "RECOVERY_REQUIRED");
        if (step.key != null) throw new FresnelProblem(409, "STEP_ALREADY_TRIGGERED");
        if (session.state == State.WAITING_FOR_EXTERNAL_TRIGGER && timedOut(session, Instant.now()))
            stop(session, State.PAUSED);
        if (session.state != State.WAITING_FOR_EXTERNAL_TRIGGER || step.sequenceIndex != session.completed())
            throw new FresnelProblem(409, "STEP_NOT_READY");
        var planned = session.plan.steps().get(step.sequenceIndex);
        FresnelPlan.validateTrigger(FresnelPlan.parse(body, FresnelPlan.Trigger.class), planned);
        step.key = key;
        step.bodyHash = hash;
        step.state = StepState.CAPTURING;
        session.event(State.CAPTURING, "STEP_RESERVED", step);
        save(session);
        recordSessionState(session);
        recordStepState(step);
        workers.schedule(() -> dispatch(session, step), planned.settleDelayMs(), TimeUnit.MILLISECONDS);
        return new TriggerResult(202, session.status());
    }

    private void dispatch(CaptureSession session, CaptureSession.Step step) {
        // Revocation and entry into capture share the same pairing lock. No token goes to disk.
        try {
            pairings.whilePaired(session.pairingId, () -> capture(session, step));
        } catch (RuntimeException failed) {
            synchronized (this) {
                if (!unavailable && session.state == State.CAPTURING) {
                    step.state = StepState.RECOVERY_REQUIRED;
                    session.event(State.RECOVERY_REQUIRED, "RECOVERY_REQUIRED", step);
                    try {
                        save(session);
                        recordSessionState(session);
                        recordStepState(step);
                    } finally { release(session); }
                }
            }
        }
    }

    private synchronized void capture(CaptureSession session, CaptureSession.Step step) {
        if (unavailable || session.state != State.CAPTURING || step.state != StepState.CAPTURING || step.dispatched) return;
        if (timedOut(session, Instant.now())) { stop(session, State.PAUSED); return; }
        var camera = active.get(session.id);
        if (camera == null) throw new IllegalStateException("Capture lease is unavailable");
        step.assetId = UUID.randomUUID().toString();
        step.dispatched = true;
        save(session); // A crash from here on fences the step, even if the camera received nothing.
        var captured = camera.backend.capture();
        byte[] bytes = captured.imageData();
        byte[] pngSignature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (bytes == null || bytes.length < pngSignature.length || bytes.length > FresnelPlan.MAX_ASSET_BYTES
                || !Arrays.equals(pngSignature, 0, pngSignature.length, bytes, 0, pngSignature.length))
            throw new IllegalStateException("Camera did not return the promised PNG representation");
        String assetId = step.assetId;
        String name = step.stepId.replace(':', '_') + ".png";
        var role = session.plan.steps().get(step.sequenceIndex).role().name();
        var asset = new CaptureSession.Asset(session.id, step.stepId, assetId, role, name, "image/png", bytes.length,
                FresnelPlan.hash(bytes), captured.capturedAt().toString());
        try {
            Path source = Files.createTempFile("fresnel-capture-", ".png");
            try {
                Files.write(source, bytes);
                media.putFile(assetId, name, source);
                Path stored = media.getOriginalFrame(assetId).path();
                try (var file = FileChannel.open(stored, StandardOpenOption.WRITE)) {
                    file.force(true);
                }
                // The filename and remaining metadata are committed in the session journal.
                CaptureJournal.syncDirectory(stored.getParent());
            } finally {
                Files.deleteIfExists(source);
            }
        } catch (IOException io) {
            throw new UncheckedIOException(io);
        }
        step.result = new CaptureSession.Result(session.id, step.stepId, asset, Instant.now().toString());
        step.state = StepState.COMPLETED;
        boolean done = session.completed() == session.steps.size();
        session.event(done ? State.COMPLETED : State.WAITING_FOR_EXTERNAL_TRIGGER, "STEP_COMPLETED", step);
        save(session);
        recordSessionState(session);
        recordStepState(step);
        metrics.counter("photographer.fresnel.capture.source.bytes").increment(asset.byteLength());
        if (done) release(session);
    }

    synchronized void cancel(String pairing, String id, String key) {
        cancel(pairing, id, key, null);
    }

    synchronized void cancel(String pairing, String id, String key, byte[] body) {
        var session = owned(pairing, id);
        FresnelPlan.requireId(key);
        if (body != null && body.length > 0)
            throw session.cancelKeys.contains(key) ? new FresnelProblem(409, "IDEMPOTENCY_CONFLICT")
                    : new FresnelProblem(400, "INVALID_REQUEST");
        if (session.cancelKeys.contains(key)) {
            recordReplay("cancel", "accepted");
            return;
        }
        if (session.cancelKeys.size() >= 32) throw new FresnelProblem(429, "CAPTURE_LIMIT");
        session.cancelKeys.add(key);
        if (session.state.terminal()) save(session);
        else stop(session, State.CANCELLED);
    }

    public synchronized void revoke(String pairingId) {
        for (var session : sessions.values()) {
            if (!session.state.terminal() && session.pairingId.equals(pairingId)) stop(session, State.CANCELLED);
        }
    }

    private static boolean timedOut(CaptureSession session, Instant now) {
        return !now.isBefore(Instant.parse(session.createdAt).plusMillis(FresnelPlan.MAX_DURATION_MS))
                || (session.state == State.WAITING_FOR_EXTERNAL_TRIGGER
                && !now.isBefore(Instant.parse(session.updatedAt).plusMillis(FresnelPlan.MAX_WAIT_MS)));
    }

    synchronized void expire(Instant now) {
        if (unavailable) return;
        if (!now.isBefore(nextPurge)) {
            purge(now);
            nextPurge = now.plus(Duration.ofHours(1));
        }
        for (String id : List.copyOf(active.keySet())) {
            var session = sessions.get(id);
            if (timedOut(session, now)) stop(session, State.PAUSED);
        }
    }

    private void purge(Instant now) {
        if (retention.isZero()) return;
        var expired = sessions.values().stream().filter(session -> session.state.terminal()
                && Instant.parse(session.updatedAt).plus(retention).isBefore(now)).toList();
        for (var session : expired) {
            if (!session.purging) {
                session.purging = true;
                save(session);
            }
            try {
                removeExpired(session);
                sessions.remove(session.id);
            } catch (IOException failure) {
                log.warn("Could not finish expired Fresnel session cleanup, will retry", failure);
            }
        }
    }

    private void removeExpired(CaptureSession session) throws IOException {
        for (var step : session.steps) {
            String id = step.result != null ? step.result.asset().assetId() : step.assetId;
            if (id != null) {
                media.remove(id);
                if (media.contains(id)) throw new IOException("Expired frame remains in storage");
            }
        }
        journal.delete(session.id);
    }

    private void stop(CaptureSession session, State target) {
        for (var step : session.steps) {
            if (step.state == StepState.PENDING || (!step.dispatched
                    && (step.state == StepState.CAPTURING || step.state == StepState.RECOVERY_REQUIRED))) {
                if (target == State.CANCELLED) step.state = StepState.CANCELLED;
                else if (step.key != null) step.state = StepState.RECOVERY_REQUIRED;
            } else if (step.state == StepState.CAPTURING) {
                step.state = StepState.RECOVERY_REQUIRED;
            }
        }
        session.event(target, target.name(), null);
        try {
            save(session);
            recordSessionState(session);
        } finally { release(session); }
    }

    synchronized Media asset(String pairing, String id, String assetId) {
        ready();
        var session = sessions.get(id);
        if (session != null && !session.purging && session.pairingId.equals(pairing)) {
            for (var step : session.steps) {
                if (step.result != null && step.verified && step.result.asset().assetId().equals(assetId)) {
                    var original = verifiedFrame(session, step);
                    if (original != null) return new Media(step.result.asset(), original.path());
                }
            }
        }
        throw new FresnelProblem(404, "ASSET_NOT_FOUND");
    }

    private FrameDataStore.OriginalFrame original(CaptureSession.Asset asset) {
        var frame = media.getOriginalFrame(asset.assetId());
        try {
            if (frame != null && asset.byteLength() <= FresnelPlan.MAX_ASSET_BYTES
                    && Files.size(frame.path()) == asset.byteLength()
                    && FresnelPlan.hash(frame.path()).equals(asset.sha256()))
                return frame;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return null;
    }

    private FrameDataStore.OriginalFrame verifiedFrame(CaptureSession session, CaptureSession.Step step) {
        var frame = original(step.result.asset());
        if (frame == null) {
            step.verified = false;
            step.state = StepState.RECOVERY_REQUIRED;
            session.event(State.RECOVERY_REQUIRED, "RECOVERY_REQUIRED", step);
            try {
                save(session);
                recordSessionState(session);
                recordStepState(step);
            } finally { release(session); }
        }
        return frame;
    }

    private void recover(CaptureSession session) {
        boolean changed = false;
        for (var step : session.steps) {
            if (step.result != null && step.verified) {
                if (original(step.result.asset()) == null) {
                    step.verified = false;
                    step.state = StepState.RECOVERY_REQUIRED;
                    changed = true;
                }
            } else if (step.key != null && step.state == StepState.CAPTURING) {
                step.state = StepState.RECOVERY_REQUIRED;
                changed = true;
            }
        }
        if ((!session.state.terminal() && session.state != State.RECOVERY_REQUIRED) || changed) {
            session.event(State.RECOVERY_REQUIRED, "RECOVERY_REQUIRED", null);
            save(session);
            recordSessionState(session);
        }
    }

    private void recordSessionState(CaptureSession session) {
        metrics.counter("photographer.fresnel.session.transitions", "state", session.state.name()).increment();
    }

    private void recordStepState(CaptureSession.Step step) {
        metrics.counter("photographer.fresnel.step.transitions", "state", step.state.name()).increment();
    }

    private void recordReplay(String operation, String result) {
        metrics.counter("photographer.fresnel.request.replays", "operation", operation, "result", result).increment();
    }

    private void save(CaptureSession session) {
        try { journal.save(session); }
        catch (IOException io) {
            unavailable = true;
            for (String id : List.copyOf(active.keySet())) release(sessions.get(id));
            throw new UncheckedIOException(io);
        }
    }

    private void release(CaptureSession session) {
        var owned = active.remove(session.id);
        if (owned != null) owned.lease.close();
    }

    synchronized void checkAvailable() { ready(); }

    private void ready() { if (unavailable) throw new FresnelProblem(503, "SERVICE_UNAVAILABLE"); }

    @EventListener(ContextClosedEvent.class)
    public void stopBeforeLeaseManagerShutdown() throws IOException { close(); }

    @Override @PreDestroy public synchronized void close() throws IOException {
        if (unavailable && workers.isShutdown()) return;
        unavailable = true;
        workers.shutdownNow();
        for (var camera : active.values()) camera.lease.close();
        active.clear();
        journal.close();
    }
}
