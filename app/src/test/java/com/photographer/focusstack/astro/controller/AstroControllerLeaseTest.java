package com.photographer.focusstack.astro.controller;

import com.photographer.focusstack.astro.service.AstroModeService;
import com.photographer.focusstack.common.model.AstroCaptureConfig;
import com.photographer.focusstack.common.model.AstroFocusConfig;
import com.photographer.focusstack.common.model.AstroSession;
import com.photographer.focusstack.common.model.AstroSessionStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.task.TaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AstroControllerLeaseTest {

    @Test
    void createPreparesOwnershipBeforeScheduling() {
        AstroModeService service = mock(AstroModeService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        AstroController controller = new AstroController(service, executor);
        AstroModeService.PreparedExecution execution =
                mock(AstroModeService.PreparedExecution.class);
        AstroSession created = session(AstroSessionStatus.CREATED);
        AstroSession preparing = session(AstroSessionStatus.PREPARING);
        when(service.createSession(created.captureConfig(), created.focusConfig()))
                .thenReturn(created);
        when(service.prepareExecution(created.id())).thenReturn(execution);
        when(service.getSession(created.id())).thenReturn(Optional.of(preparing));
        AstroController.CreateAstroSessionRequest request =
                new AstroController.CreateAstroSessionRequest(
                        created.captureConfig(), created.focusConfig());

        var response = controller.createSession(request);

        assertThat(response.getBody()).isEqualTo(preparing);
        InOrder order = inOrder(service, executor);
        order.verify(service).createSession(created.captureConfig(), created.focusConfig());
        order.verify(service).prepareExecution(created.id());
        order.verify(executor).execute(execution);
    }

    @Test
    void executorRejectionRejectsPreparedExecution() {
        AstroModeService service = mock(AstroModeService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        AstroController controller = new AstroController(service, executor);
        AstroModeService.PreparedExecution execution =
                mock(AstroModeService.PreparedExecution.class);
        AstroSession created = session(AstroSessionStatus.CREATED);
        when(service.createSession(created.captureConfig(), created.focusConfig()))
                .thenReturn(created);
        when(service.prepareExecution(created.id())).thenReturn(execution);
        IllegalStateException rejection = new IllegalStateException("executor rejected");
        doThrow(rejection).when(executor).execute(execution);
        AstroController.CreateAstroSessionRequest request =
                new AstroController.CreateAstroSessionRequest(
                        created.captureConfig(), created.focusConfig());

        assertThatThrownBy(() -> controller.createSession(request))
                .isSameAs(rejection);

        verify(execution).reject(rejection);
    }

    @Test
    void abortResponseDoesNotReflectSessionIdentifier() {
        AstroModeService service = mock(AstroModeService.class);
        AstroController controller = new AstroController(service, Runnable::run);

        var response = controller.abortSession("private-session-id");

        assertThat(response.getBody()).containsEntry("message", "Abort requested");
        assertThat(response.getBody().toString()).doesNotContain("private-session-id");
    }

    private static AstroSession session(AstroSessionStatus status) {
        return new AstroSession(
                "astro-session", status, Instant.now(), null, null,
                new AstroCaptureConfig(
                        "real-camera", 1, 1000, 800, null,
                        0, "PNG", false, true, false),
                AstroFocusConfig.manualFixed(),
                "data/astro-session", List.of(), null, null, null);
    }
}
