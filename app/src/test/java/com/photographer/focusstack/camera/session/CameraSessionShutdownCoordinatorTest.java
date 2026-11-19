package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.camera.exception.CameraServiceUnavailableException;
import com.photographer.focusstack.astro.service.AstroModeService;
import com.photographer.focusstack.camera.service.StackingJobService;
import com.photographer.focusstack.observer.service.LocalBirdObserverService;
import com.photographer.focusstack.session.FaceOrbitService;
import com.photographer.focusstack.session.HdrTimelapseService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
        LocalBirdObserverService observer = mock(LocalBirdObserverService.class);
        AtomicLong time = new AtomicLong();

        CameraSessionShutdownCoordinator coordinator = new CameraSessionShutdownCoordinator(
      manager, stacking, hdr, face, astro, observer,
      Duration.ofSeconds(1), Duration.ofMillis(1),
      time::get, duration -> {
          lease.close();
          time.addAndGet(duration.toNanos());
      });

        coordinator.shutdown();

        var order = inOrder(stacking, hdr, face, astro, observer);
        order.verify(stacking).requestCameraShutdown();
        order.verify(hdr).requestCameraShutdown();
        order.verify(face).requestCameraShutdown();
        order.verify(astro).requestCameraShutdown();
        order.verify(observer).requestCameraShutdown();
        assertThat(manager.metrics().activeLeases()).isZero();
        assertThatThrownBy(() -> manager.acquire(new CameraLeaseRequest(
      CameraLeaseKey.fromCcapiLocation("camera.example"),
      CameraWorkflowType.DIRECT_CONTROL,
      "later", "later-owner", Duration.ofMinutes(5))))
      .isInstanceOf(CameraServiceUnavailableException.class)
      .hasMessage("Camera services are shutting down");

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
        LocalBirdObserverService observer = mock(LocalBirdObserverService.class);
        AtomicLong time = new AtomicLong();

        CameraSessionShutdownCoordinator coordinator = new CameraSessionShutdownCoordinator(
      manager, stacking, hdr, face, astro, observer,
      Duration.ofMillis(2), Duration.ofMillis(1),
      time::get, duration -> time.addAndGet(duration.toNanos()));

        assertThatThrownBy(coordinator::shutdown)
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("3 active workflow(s)")
      .hasMessageContaining("2 active lease(s)")
      .hasMessageNotContaining("camera.example")
      .hasMessageNotContaining("owner");
    }

    @Test
    void shutdownRequestsObserverStopAndWaitsForItsExecutionEvenWithoutALease() {
        LocalBirdObserverService observer = mock(LocalBirdObserverService.class);
        when(observer.activeCameraExecutions()).thenReturn(1);

        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        ApplicationConversionService.getSharedInstance()))
                .withPropertyValues("camera.session-lease.shutdown-timeout=PT0.005S",
                        "camera.session-lease.shutdown-poll-interval=PT0.001S")
                .withBean(CameraSessionLeaseManager.class, () -> new InMemoryCameraSessionLeaseManager(
                        Duration.ofHours(6), 1024))
                .withBean(StackingJobService.class, () -> mock(StackingJobService.class))
                .withBean(HdrTimelapseService.class, () -> mock(HdrTimelapseService.class))
                .withBean(FaceOrbitService.class, () -> mock(FaceOrbitService.class))
                .withBean(AstroModeService.class, () -> mock(AstroModeService.class))
                .withBean(LocalBirdObserverService.class, () -> observer)
                .withUserConfiguration(CameraSessionShutdownCoordinator.class)
                .run(context -> {
                    CameraSessionShutdownCoordinator coordinator =
                            context.getBean(CameraSessionShutdownCoordinator.class);
                    assertThatThrownBy(coordinator::shutdown)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("1 active workflow(s) and 0 active lease(s)");
                    var order = inOrder(observer);
                    order.verify(observer).requestCameraShutdown();
                });
    }
}
