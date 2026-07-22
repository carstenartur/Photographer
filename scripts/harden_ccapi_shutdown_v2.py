from pathlib import Path


def replace_once(path_name: str, old: str, new: str) -> None:
    path = Path(path_name)
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{path_name}: expected one anchor, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new))


def replace_method(
        path_name: str,
        signature: str,
        next_signature: str,
        replacement: str) -> None:
    path = Path(path_name)
    text = path.read_text()
    start = text.index(signature)
    end = text.index(next_signature, start)
    path.write_text(text[:start] + replacement + "\n\n" + text[end:])


Path(
    "app/src/main/java/com/photographer/focusstack/camera/exception/"
    "CameraServiceUnavailableException.java"
).write_text(
    """package com.photographer.focusstack.camera.exception;

/** Fixed, identity-free signal for requests racing with application shutdown. */
public final class CameraServiceUnavailableException extends CameraException {
    public CameraServiceUnavailableException() {
        super("Camera services are shutting down");
    }
}
"""
)

Path(
    "session-core/src/main/java/com/photographer/focusstack/session/"
    "SessionUnavailableException.java"
).write_text(
    """package com.photographer.focusstack.session;

/** Fixed, identity-free signal for session starts racing with application shutdown. */
public final class SessionUnavailableException extends SessionException {
    public SessionUnavailableException() {
        super("Camera services are shutting down");
    }
}
"""
)

stacking = (
    "app/src/main/java/com/photographer/focusstack/camera/service/"
    "StackingJobService.java"
)
replace_once(
    stacking,
    "import com.photographer.focusstack.camera.exception.CameraException;",
    "import com.photographer.focusstack.camera.exception.CameraException;\n"
    "import com.photographer.focusstack.camera.exception."
    "CameraServiceUnavailableException;",
)
path = Path(stacking)
text = path.read_text()
start = text.index("    public void requestCameraShutdown() {")
end = text.index("    public int activeCameraExecutions()", start)
replacement = """    public void requestCameraShutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return;
        }
        List.copyOf(activeAttempts.keySet()).forEach(jobId -> {
            synchronized (jobLock(jobId)) {
                ActiveStackingAttempt attempt = activeAttempts.get(jobId);
                if (attempt == null) {
                    return;
                }
                cancelledJobs.add(jobId);
                if (attempt.cancelBeforeStart()) {
                    activeAttempts.remove(jobId, attempt);
                    persistShutdownCancellation(jobId);
                }
            }
        });
    }

    private void persistShutdownCancellation(String jobId) {
        synchronized (jobLock(jobId)) {
            repository.findById(jobId).ifPresent(entity -> {
                StackingJob current = entity.toRecord();
                if (current.status() == JobStatus.COMPLETED
                        || current.status() == JobStatus.FAILED
                        || current.status() == JobStatus.CANCELLED) {
                    return;
                }
                repository.save(StackingJobEntity.fromRecord(new StackingJob(
                        current.id(), current.cameraProfileId(), JobStatus.CANCELLED,
                        current.startedAt(), Instant.now(), current.parameters(),
                        new ArrayList<>(current.frames()),
                        "Cancelled during application shutdown",
                        current.executionMetadata())));
            });
        }
    }

"""
path.write_text(text[:start] + replacement + text[end:])
replace_once(
    stacking,
    """        } finally {
            activeAttempts.remove(job.id(), attempt);
        }""",
    """        } finally {
            activeAttempts.remove(job.id(), attempt);
            if (shutdownRequested.get() && cancelledJobs.contains(job.id())) {
                persistShutdownCancellation(job.id());
            }
        }""",
)
replace_method(
    stacking,
    "    private void ensureCameraServiceOpen() {",
    "    public StackingJob getJob",
    """    private void ensureCameraServiceOpen() {
        if (shutdownRequested.get()) {
            throw new CameraServiceUnavailableException();
        }
    }""",
)

manager = (
    "app/src/main/java/com/photographer/focusstack/camera/session/"
    "InMemoryCameraSessionLeaseManager.java"
)
replace_once(
    manager,
    "package com.photographer.focusstack.camera.session;\n",
    "package com.photographer.focusstack.camera.session;\n\n"
    "import com.photographer.focusstack.camera.exception."
    "CameraServiceUnavailableException;\n",
)
replace_once(
    manager,
    'throw new IllegalStateException("Camera session lease manager is shutting down");',
    "throw new CameraServiceUnavailableException();",
)

hdr = "session-core/src/main/java/com/photographer/focusstack/session/HdrTimelapseService.java"
replace_method(
    hdr,
    "    private void ensureCameraServiceOpen() {",
    "    public void stopSession",
    """    private void ensureCameraServiceOpen() {
        if (shutdownRequested.get()) {
            throw new SessionUnavailableException();
        }
    }""",
)
face = "session-core/src/main/java/com/photographer/focusstack/session/FaceOrbitService.java"
replace_method(
    face,
    "    private void ensureCameraServiceOpen() {",
    "    public void stopSession",
    """    private void ensureCameraServiceOpen() {
        if (shutdownRequested.get()) {
            throw new SessionUnavailableException();
        }
    }""",
)
astro = "app/src/main/java/com/photographer/focusstack/astro/service/AstroModeService.java"
replace_once(
    astro,
    "import com.photographer.focusstack.session.SessionException;",
    "import com.photographer.focusstack.session.SessionException;\n"
    "import com.photographer.focusstack.session.SessionUnavailableException;",
)
replace_method(
    astro,
    "    private void ensureCameraServiceOpen() {",
    "    public void requestAbort",
    """    private void ensureCameraServiceOpen() {
        if (shutdownRequested.get()) {
            throw new SessionUnavailableException();
        }
    }""",
)

handler = (
    "app/src/main/java/com/photographer/focusstack/app/web/exception/"
    "GlobalExceptionHandler.java"
)
replace_once(
    handler,
    "import com.photographer.focusstack.camera.exception.CameraOperationException;",
    "import com.photographer.focusstack.camera.exception.CameraOperationException;\n"
    "import com.photographer.focusstack.camera.exception."
    "CameraServiceUnavailableException;",
)
replace_once(
    handler,
    "import com.photographer.focusstack.session.SessionNotFoundException;",
    "import com.photographer.focusstack.session.SessionNotFoundException;\n"
    "import com.photographer.focusstack.session.SessionUnavailableException;",
)
replace_once(
    handler,
    "    @ExceptionHandler(CameraSessionConflictException.class)",
    """    @ExceptionHandler({
            CameraServiceUnavailableException.class,
            SessionUnavailableException.class
    })
    public ProblemDetail handleCameraServiceUnavailable(RuntimeException ignored) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Camera services are shutting down");
        problem.setTitle("Camera Service Unavailable");
        problem.setProperty("code", "CAMERA_SERVICE_SHUTTING_DOWN");
        problem.setProperty("retryable", false);
        return problem;
    }

    @ExceptionHandler(CameraSessionConflictException.class)""",
)

manager_test = (
    "app/src/test/java/com/photographer/focusstack/camera/session/"
    "InMemoryCameraSessionLeaseManagerTest.java"
)
replace_once(
    manager_test,
    "import org.junit.jupiter.api.AfterEach;",
    "import com.photographer.focusstack.camera.exception."
    "CameraServiceUnavailableException;\n"
    "import org.junit.jupiter.api.AfterEach;",
)
replace_once(
    manager_test,
    """        assertThatThrownBy(() -> manager.acquire(request(key, "owner")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shutting down");""",
    """        assertThatThrownBy(() -> manager.acquire(request(key, "owner")))
                .isInstanceOf(CameraServiceUnavailableException.class)
                .hasMessage("Camera services are shutting down");""",
)

stacking_test = (
    "app/src/test/java/com/photographer/focusstack/camera/service/"
    "StackingJobLeaseIntegrationTest.java"
)
replace_once(
    stacking_test,
    "import com.photographer.focusstack.camera.exception.CameraException;",
    "import com.photographer.focusstack.camera.exception.CameraException;\n"
    "import com.photographer.focusstack.camera.exception."
    "CameraServiceUnavailableException;",
)
replace_once(
    stacking_test,
    "    private StackingJobService service(",
    """    @Test
    void shutdownCancelsQueuedAttemptPersistentlyWithoutCameraContact() {
        InMemoryCameraSessionLeaseManager manager = manager();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        StackingJobService service = service(manager, scheduled::set);

        service.startJob("job-1");
        service.requestCameraShutdown();

        assertThat(manager.metrics().activeLeases()).isZero();
        assertThat(service.activeCameraExecutions()).isZero();
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(persistedJob.get().getStopReason())
                .isEqualTo("Cancelled during application shutdown");
        scheduled.get().run();
        verify(backendFactory, never()).getBackend(any());
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void shutdownRejectsLaterStartBeforeLeaseOrBackendContact() {
        InMemoryCameraSessionLeaseManager manager = manager();
        StackingJobService service = service(manager, Runnable::run);
        service.requestCameraShutdown();

        assertThatThrownBy(() -> service.startJob("job-1"))
                .isInstanceOf(CameraServiceUnavailableException.class)
                .hasMessage("Camera services are shutting down");

        assertThat(manager.metrics().activeLeases()).isZero();
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.CREATED);
        verify(backendFactory, never()).getBackend(any());
    }

    private StackingJobService service(""",
)

Path(
    "app/src/test/java/com/photographer/focusstack/app/web/exception/"
    "GlobalExceptionHandlerShutdownTest.java"
).write_text(
    """package com.photographer.focusstack.app.web.exception;

import com.photographer.focusstack.camera.exception.CameraServiceUnavailableException;
import com.photographer.focusstack.session.SessionUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerShutdownTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void cameraAndSessionShutdownUseTheSameFixed503Contract() {
        for (RuntimeException failure : new RuntimeException[]{
                new CameraServiceUnavailableException(),
                new SessionUnavailableException()}) {
            ProblemDetail problem = handler.handleCameraServiceUnavailable(failure);
            assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
            assertThat(problem.getTitle()).isEqualTo("Camera Service Unavailable");
            assertThat(problem.getDetail()).isEqualTo("Camera services are shutting down");
            assertThat(problem.getProperties())
                    .containsEntry("code", "CAMERA_SERVICE_SHUTTING_DOWN")
                    .containsEntry("retryable", false);
            assertThat(problem.toString())
                    .doesNotContain("camera.example")
                    .doesNotContain("owner");
        }
    }
}
"""
)
