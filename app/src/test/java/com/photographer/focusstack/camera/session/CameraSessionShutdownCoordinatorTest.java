package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.astro.service.AstroModeService;
import com.photographer.focusstack.camera.service.StackingJobService;
import com.photographer.focusstack.session.FaceOrbitService;
import com.photographer.focusstack.session.HdrTimelapseService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CameraSessionShutdownCoordinatorTest {

    @Test
    void blocksNewLeasesStopsEveryWorkflowAndWaitsForExistingHandle() {
        InMemoryCameraSessionLeaseManager manager = new InMemoryCameraSessionLeaseManager(
      Duration.ofHours(6), 1024);
        CameraSessionLease lease = manager.acquire(new CameraLeaseRequest(
      CameraLeaseKey.fromCcapiLocation("camera.example"),
      CameraWorkflowType.DIRECT_CONTROL,
      "request", "owner", Duration.ofMinutes(5)));
        StackingJobService stacking = mock(StackingJobService.class);
        HdrTimelapseService hdr = mock(HdrTimelapseService.class);
        FaceOrbitService face = mock(FaceOrbitService.class);
        AstroModeService astro = mock(AstroModeService.class);
        AtomicLong time = new AtomicLong();

        CameraSessionShutdownCoordinator coordinator = new CameraSessionShutdownCoordinator(
      manager, stacking, hdr, face, astro,
      Duration.ofSeconds(1), Duration.ofMillis(1),
      time::get, duration -> {
          lease.close();
          time.addAndGet(duration.toNanos());
      });

        coordinator.shutdown();

        var order = inOrder(stacking, hdr, face, astro);
        order.verify(stacking).requestCameraShutdown();
        order.verify(hdr).requestCameraShutdown();
        order.verify(face).requestCameraShutdown();
        order.verify(astro).requestCameraShutdown();
        assertThat(manager.metrics().activeLeases()).isZero();
        assertThatThrownBy(() -> manager.acquire(new CameraLeaseRequest(
      CameraLeaseKey.fromCcapiLocation("camera.example"),
      CameraWorkflowType.DIRECT_CONTROL,
      "later", "later-owner", Duration.ofMinutes(5))))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("shutting down");

        coordinator.shutdown();
    }

    @Test
    void timeoutReportsCountsWithoutCameraIdentity() {
        CameraSessionLeaseManager manager = mock(CameraSessionLeaseManager.class);
        when(manager.metrics()).thenReturn(new CameraSessionLeaseMetrics(2, 0, 0));
        StackingJobService stacking = mock(StackingJobService.class);
        when(stacking.activeCameraExecutions()).thenReturn(1);
        HdrTimelapseService hdr = mock(HdrTimelapseService.class);
        when(hdr.activeCameraExecutions()).thenReturn(2);
        FaceOrbitService face = mock(FaceOrbitService.class);
        AstroModeService astro = mock(AstroModeService.class);
        AtomicLong time = new AtomicLong();

        CameraSessionShutdownCoordinator coordinator = new CameraSessionShutdownCoordinator(
      manager, stacking, hdr, face, astro,
      Duration.ofMillis(2), Duration.ofMillis(1),
      time::get, duration -> time.addAndGet(duration.toNanos()));

        assertThatThrownBy(coordinator::shutdown)
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("3 active workflow(s)")
      .hasMessageContaining("2 active lease(s)")
      .hasMessageNotContaining("camera.example")
      .hasMessageNotContaining("owner");
    }
}
