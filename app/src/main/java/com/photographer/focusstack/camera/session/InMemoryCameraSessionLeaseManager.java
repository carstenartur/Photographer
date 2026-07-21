package com.photographer.focusstack.camera.session;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Process-local lease manager for Canon's one-opposing-device constraint.
 *
 * <p>Lease ownership is independent of backend caching. A live lease is never
 * stolen because its hard deadline elapsed; every active handle must close.</p>
 */
@Component
public final class InMemoryCameraSessionLeaseManager implements CameraSessionLeaseManager {
    private static final HexFormat HEX = HexFormat.of();
    private static final Duration MAX_PUBLIC_RETRY_AFTER = Duration.ofSeconds(30);

    private final ConcurrentMap<CameraLeaseKey, LeaseState> leases = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Supplier<String> correlationIdSupplier;
    private final Duration maximumLifetime;
    private final int maximumReentrantHandles;
    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong staleRecoveries = new AtomicLong();

    public InMemoryCameraSessionLeaseManager(
            @Value("${camera.session-lease.maximum-lifetime:PT6H}") Duration maximumLifetime,
            @Value("${camera.session-lease.maximum-reentrant-handles:1024}") int maximumReentrantHandles) {
        this(Clock.systemUTC(), InMemoryCameraSessionLeaseManager::newCorrelationId,
                maximumLifetime, maximumReentrantHandles);
    }

    InMemoryCameraSessionLeaseManager(
            Clock clock,
            Supplier<String> correlationIdSupplier,
            Duration maximumLifetime,
            int maximumReentrantHandles) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.correlationIdSupplier = Objects.requireNonNull(
                correlationIdSupplier, "correlationIdSupplier");
        this.maximumLifetime = requirePositive(maximumLifetime, "maximumLifetime");
        if (maximumReentrantHandles < 1) {
            throw new IllegalArgumentException("maximumReentrantHandles must be positive");
        }
        this.maximumReentrantHandles = maximumReentrantHandles;
    }

    @Override
    public CameraSessionLease acquire(CameraLeaseRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.requestedLifetime().compareTo(maximumLifetime) > 0) {
            throw new IllegalArgumentException("Requested camera lease lifetime exceeds the configured maximum");
        }

        Instant now = clock.instant();
        AtomicReference<LeaseState> acquired = new AtomicReference<>();
        leases.compute(request.cameraKey(), (key, current) -> {
            if (current == null) {
                LeaseState created = new LeaseState(
                        request.ownerToken(),
                        validatedCorrelationId(correlationIdSupplier.get()),
                        request.workflowType(),
                        now.plus(request.requestedLifetime()),
                        1);
                acquired.set(created);
                return created;
            }
            if (!current.ownerToken().equals(request.ownerToken())) {
                conflicts.incrementAndGet();
                throw new CameraSessionConflictException(
                        current.correlationId(),
                        current.workflowType(),
                        retryAfter(now, current.hardDeadline()));
            }
            if (current.handleCount() >= maximumReentrantHandles) {
                throw new IllegalStateException("Camera lease reentrant handle limit exceeded");
            }
            LeaseState reentrant = current.withHandleCount(current.handleCount() + 1);
            acquired.set(reentrant);
            return reentrant;
        });

        LeaseState state = acquired.get();
        if (state == null) {
            throw new IllegalStateException("Camera lease acquisition did not produce a handle");
        }
        return new Handle(this, request.cameraKey(), request.ownerToken(), state.correlationId());
    }

    @Override
    public CameraSessionLeaseMetrics metrics() {
        return new CameraSessionLeaseMetrics(
                leases.size(), conflicts.get(), staleRecoveries.get());
    }

    /**
     * Defensive cleanup for an impossible zero-reference residue. Active leases,
     * including expired ones, are never removed.
     */
    int reapStaleLeases() {
        Instant now = clock.instant();
        AtomicLong recovered = new AtomicLong();
        leases.forEach((key, ignored) -> leases.computeIfPresent(key, (cameraKey, current) -> {
            if (current.handleCount() == 0 && !current.hardDeadline().isAfter(now)) {
                recovered.incrementAndGet();
                return null;
            }
            return current;
        }));
        staleRecoveries.addAndGet(recovered.get());
        return Math.toIntExact(recovered.get());
    }

    @PreDestroy
    public void close() {
        leases.clear();
    }

    private void release(CameraLeaseKey key, String ownerToken, String correlationId) {
        leases.computeIfPresent(key, (cameraKey, current) -> {
            if (!current.ownerToken().equals(ownerToken)
                    || !current.correlationId().equals(correlationId)) {
                return current;
            }
            int remaining = current.handleCount() - 1;
            return remaining == 0 ? null : current.withHandleCount(remaining);
        });
    }

    private static Duration retryAfter(Instant now, Instant hardDeadline) {
        Duration remaining = Duration.between(now, hardDeadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ZERO;
        }
        return remaining.compareTo(MAX_PUBLIC_RETRY_AFTER) > 0
                ? MAX_PUBLIC_RETRY_AFTER
                : remaining;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String validatedCorrelationId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new IllegalStateException("Correlation ID provider returned an invalid value");
        }
        return value;
    }

    private static String newCorrelationId() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record LeaseState(
            String ownerToken,
            String correlationId,
            CameraWorkflowType workflowType,
            Instant hardDeadline,
            int handleCount) {

        private LeaseState {
            Objects.requireNonNull(ownerToken, "ownerToken");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(workflowType, "workflowType");
            Objects.requireNonNull(hardDeadline, "hardDeadline");
            if (handleCount < 0) {
                throw new IllegalArgumentException("handleCount must not be negative");
            }
        }

        LeaseState withHandleCount(int value) {
            return new LeaseState(ownerToken, correlationId, workflowType, hardDeadline, value);
        }
    }

    private static final class Handle implements CameraSessionLease {
        private final InMemoryCameraSessionLeaseManager manager;
        private final CameraLeaseKey key;
        private final String ownerToken;
        private final String correlationId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Handle(
                InMemoryCameraSessionLeaseManager manager,
                CameraLeaseKey key,
                String ownerToken,
                String correlationId) {
            this.manager = manager;
            this.key = key;
            this.ownerToken = ownerToken;
            this.correlationId = correlationId;
        }

        @Override
        public String ownerCorrelationId() {
            return correlationId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                manager.release(key, ownerToken, correlationId);
            }
        }
    }
}
