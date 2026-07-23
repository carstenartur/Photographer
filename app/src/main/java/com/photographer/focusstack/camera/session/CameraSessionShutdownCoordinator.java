package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.astro.service.AstroModeService;
import com.photographer.focusstack.camera.service.StackingJobService;
import com.photographer.focusstack.session.FaceOrbitService;
import com.photographer.focusstack.session.HdrTimelapseService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Coordinates cooperative camera shutdown before transports and lease state are destroyed. */
@Component
public final class CameraSessionShutdownCoordinator {
    private static final Logger log = LoggerFactory.getLogger(CameraSessionShutdownCoordinator.class);

    @FunctionalInterface
    interface Waiter {
        void await(Duration duration) throws InterruptedException;
    }

    private final CameraSessionLeaseManager leaseManager;
    private final StackingJobService stackingJobs;
    private final HdrTimelapseService hdrTimelapse;
    private final FaceOrbitService faceOrbit;
    private final AstroModeService astroMode;
    private final Duration timeout;
    private final Duration pollInterval;
    private final LongSupplier nanoTime;
    private final Waiter waiter;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    @Autowired
    public CameraSessionShutdownCoordinator(
  CameraSessionLeaseManager leaseManager,
  StackingJobService stackingJobs,
  HdrTimelapseService hdrTimelapse,
  FaceOrbitService faceOrbit,
  AstroModeService astroMode,
  @Value("${camera.session-lease.shutdown-timeout:PT30S}") Duration timeout,
  @Value("${camera.session-lease.shutdown-poll-interval:PT0.05S}") Duration pollInterval) {
        this(leaseManager, stackingJobs, hdrTimelapse, faceOrbit, astroMode,
      timeout, pollInterval, System::nanoTime,
      Thread::sleep);
    }

    CameraSessionShutdownCoordinator(
  CameraSessionLeaseManager leaseManager,
  StackingJobService stackingJobs,
  HdrTimelapseService hdrTimelapse,
  FaceOrbitService faceOrbit,
  AstroModeService astroMode,
  Duration timeout,
  Duration pollInterval,
  LongSupplier nanoTime,
  Waiter waiter) {
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.stackingJobs = Objects.requireNonNull(stackingJobs, "stackingJobs");
        this.hdrTimelapse = Objects.requireNonNull(hdrTimelapse, "hdrTimelapse");
        this.faceOrbit = Objects.requireNonNull(faceOrbit, "faceOrbit");
        this.astroMode = Objects.requireNonNull(astroMode, "astroMode");
        this.timeout = positive(timeout, "timeout");
        this.pollInterval = positive(pollInterval, "pollInterval");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
    }

    @PreDestroy
    public void close() {
        shutdown();
    }

    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
  return;
        }
        leaseManager.beginShutdown();
        stackingJobs.requestCameraShutdown();
        hdrTimelapse.requestCameraShutdown();
        faceOrbit.requestCameraShutdown();
        astroMode.requestCameraShutdown();

        long deadline = Math.addExact(nanoTime.getAsLong(), timeout.toNanos());
        while (true) {
  int executions = activeExecutions();
  int leases = leaseManager.metrics().activeLeases();
  if (executions == 0 && leases == 0) {
      log.info("Camera shutdown completed with zero active workflows and leases");
      return;
  }
  if (nanoTime.getAsLong() >= deadline) {
      throw new IllegalStateException(
              "Camera shutdown timed out with " + executions
                      + " active workflow(s) and " + leases + " active lease(s)");
  }
  try {
      waiter.await(pollInterval);
  } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Camera shutdown was interrupted", interrupted);
  }
        }
    }

    private int activeExecutions() {
        return stackingJobs.activeCameraExecutions()
      + hdrTimelapse.activeCameraExecutions()
      + faceOrbit.activeCameraExecutions()
      + astroMode.activeCameraExecutions();
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
  throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
