package com.photographer.focusstack.camera.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryCameraSessionLeaseManagerTest {
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void closeExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void thirtyTwoDifferentOwnersProduceExactlyOneWinner() throws Exception {
        InMemoryCameraSessionLeaseManager manager = manager(new MutableClock());
        CameraLeaseKey key = CameraLeaseKey.fromCcapiLocation("camera.example");
        ExecutorService executor = Executors.newFixedThreadPool(32);
        executors.add(executor);
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CameraSessionLease>> futures = new ArrayList<>();

        for (int index = 0; index < 32; index++) {
            String owner = "owner-" + index;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return manager.acquire(request(key, owner));
                } catch (CameraSessionConflictException conflict) {
                    return null;
                }
            }));
        }
        ready.await();
        start.countDown();

        List<CameraSessionLease> winners = new ArrayList<>();
        for (Future<CameraSessionLease> future : futures) {
            CameraSessionLease lease = future.get();
            if (lease != null) {
                winners.add(lease);
            }
        }

        assertThat(winners).hasSize(1);
        assertThat(manager.metrics())
                .isEqualTo(new CameraSessionLeaseMetrics(1, 31, 0));
        winners.getFirst().close();
        assertThat(manager.metrics().activeLeases()).isZero();
    }

    @Test
    void sameOwnerIsReentrantAndPartialCloseDoesNotRelease() {
        InMemoryCameraSessionLeaseManager manager = manager(new MutableClock());
        CameraLeaseKey key = CameraLeaseKey.fromCcapiLocation("camera.example");

        CameraSessionLease first = manager.acquire(request(key, "same-owner"));
        CameraSessionLease second = manager.acquire(request(key, "same-owner"));

        assertThat(second.ownerCorrelationId()).isEqualTo(first.ownerCorrelationId());
        first.close();
        first.close();
        assertThat(manager.metrics().activeLeases()).isEqualTo(1);
        assertThatThrownBy(() -> manager.acquire(request(key, "other-owner")))
                .isInstanceOf(CameraSessionConflictException.class);

        second.close();
        assertThat(manager.metrics().activeLeases()).isZero();
        try (CameraSessionLease retry = manager.acquire(request(key, "other-owner"))) {
            assertThat(retry.ownerCorrelationId()).isNotEqualTo(first.ownerCorrelationId());
        }
    }

    @Test
    void hardDeadlineNeverStealsAReferencedLease() {
        MutableClock clock = new MutableClock();
        InMemoryCameraSessionLeaseManager manager = manager(clock);
        CameraLeaseKey key = CameraLeaseKey.fromCcapiLocation("camera.example");
        CameraSessionLease active = manager.acquire(new CameraLeaseRequest(
                key, CameraWorkflowType.FOCUS_STACKING,
                "job-1", "owner-1", Duration.ofSeconds(1)));

        clock.advance(Duration.ofHours(1));

        assertThat(manager.reapStaleLeases()).isZero();
        CameraSessionConflictException conflict = (CameraSessionConflictException)
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> manager.acquire(request(key, "owner-2")));
        assertThat(conflict).isNotNull();
        assertThat(conflict.retryAfter()).isZero();
        assertThat(manager.metrics().activeLeases()).isEqualTo(1);

        active.close();
        try (CameraSessionLease ignored = manager.acquire(request(key, "owner-2"))) {
            assertThat(manager.metrics().activeLeases()).isEqualTo(1);
        }
    }

    @Test
    void distinctOriginsDoNotConflict() {
        InMemoryCameraSessionLeaseManager manager = manager(new MutableClock());
        CameraLeaseKey firstKey = CameraLeaseKey.fromCcapiLocation("camera-one.example");
        CameraLeaseKey secondKey = CameraLeaseKey.fromCcapiLocation("camera-two.example");

        try (CameraSessionLease first = manager.acquire(request(firstKey, "owner-1"));
             CameraSessionLease second = manager.acquire(request(secondKey, "owner-2"))) {
            assertThat(manager.metrics().activeLeases()).isEqualTo(2);
            assertThat(first.ownerCorrelationId()).isNotEqualTo(second.ownerCorrelationId());
        }
    }

    @Test
    void conflictContainsOnlyRedactedBoundedDiagnostics() {
        InMemoryCameraSessionLeaseManager manager = manager(new MutableClock());
        CameraLeaseKey key = CameraLeaseKey.fromCcapiLocation(
                "http://private-camera.example:8080/ccapi");
        try (CameraSessionLease ignored = manager.acquire(request(key, "owner-secret-one"))) {
            CameraSessionConflictException conflict = (CameraSessionConflictException)
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> manager.acquire(request(key, "owner-secret-two")));

            assertThat(conflict.code()).isEqualTo("CAMERA_SESSION_CONFLICT");
            assertThat(conflict.ownerCorrelationId()).matches("corr[0-9]{8}");
            assertThat(conflict.workflowType())
                    .isEqualTo(CameraWorkflowType.FOCUS_STACKING);
            assertThat(conflict.retryAfter()).isBetween(Duration.ZERO, Duration.ofSeconds(30));
            assertThat(conflict.getMessage())
                    .doesNotContain("private-camera")
                    .doesNotContain("owner-secret")
                    .doesNotContain("8080");
        }
    }

    @Test
    void rejectsUnboundedLifetimeAndHandleCount() {
        InMemoryCameraSessionLeaseManager manager = manager(new MutableClock());
        CameraLeaseKey key = CameraLeaseKey.fromCcapiLocation("camera.example");

        assertThatThrownBy(() -> manager.acquire(new CameraLeaseRequest(
                key, CameraWorkflowType.FOCUS_STACKING,
                "job", "owner", Duration.ofHours(7))))
                .isInstanceOf(IllegalArgumentException.class);

        InMemoryCameraSessionLeaseManager singleHandleManager =
                new InMemoryCameraSessionLeaseManager(
                        new MutableClock(), correlationSupplier(), Duration.ofHours(6), 1);
        try (CameraSessionLease ignored = singleHandleManager.acquire(request(key, "owner"))) {
            assertThatThrownBy(() -> singleHandleManager.acquire(request(key, "owner")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("handle limit");
        }
    }

    @Test
    void shutdownRejectsNewHandlesAndCloseRequiresExistingHandlesToRelease() {
        InMemoryCameraSessionLeaseManager manager = manager(new MutableClock());
        CameraLeaseKey key = CameraLeaseKey.fromCcapiLocation("camera.example");
        CameraSessionLease active = manager.acquire(request(key, "owner"));

        manager.beginShutdown();

        assertThatThrownBy(() -> manager.acquire(request(key, "owner")))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("shutting down");
        assertThatThrownBy(manager::close)
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("1 active lease");

        active.close();
        manager.close();
        assertThat(manager.metrics().activeLeases()).isZero();
    }

    private static InMemoryCameraSessionLeaseManager manager(Clock clock) {
        return new InMemoryCameraSessionLeaseManager(
                clock, correlationSupplier(), Duration.ofHours(6), 1024);
    }

    private static java.util.function.Supplier<String> correlationSupplier() {
        AtomicInteger sequence = new AtomicInteger();
        return () -> "corr" + "%08d".formatted(sequence.incrementAndGet());
    }

    private static CameraLeaseRequest request(CameraLeaseKey key, String owner) {
        return new CameraLeaseRequest(
                key, CameraWorkflowType.FOCUS_STACKING,
                "job-1", owner, Duration.ofMinutes(30));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-21T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported in this test clock");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
