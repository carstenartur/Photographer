package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.analysis.service.StackMerger;
import com.photographer.focusstack.analysis.service.StackingAdvisor;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.config.PlainWebSocketBridge;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.camera.repository.StackingJobRepository;
import com.photographer.focusstack.camera.strategy.AdaptiveStepStrategy;
import com.photographer.focusstack.camera.strategy.FocusStepStrategy;
import com.photographer.focusstack.camera.strategy.LinearStepStrategy;
import com.photographer.focusstack.camera.strategy.NativeFocusBracketingExecutor;
import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionProvider;
import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.StackingCaptureStrategy;
import com.photographer.focusstack.common.model.StackingExecutionMetadata;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StackingJobService {

    private static final Logger log = LoggerFactory.getLogger(StackingJobService.class);
    private static final int MAX_PREVIEW_MERGE_FRAMES = 20;
    private static final int NATIVE_DOWNLOAD_CHUNK_BYTES = 16 * 1024 * 1024;
    private static final long MAX_NATIVE_FRAME_BYTES = 1024L * 1024L * 1024L;

    private final CameraBackendFactory backendFactory;
    private final CameraProfileService profileService;
    private final StackingJobRepository repository;
    private final SimpMessagingTemplate messagingTemplate;
    private final StackingAdvisor stackingAdvisor;
    private final StackMerger stackMerger;
    private final FocusStepStrategy stepStrategy;
    private final TaskExecutor taskExecutor;
    private final FrameDataStore frameDataStore;
    private final PlainWebSocketBridge plainWebSocketBridge;
    private final NativeFocusBracketingExecutor nativeExecutor =
            new NativeFocusBracketingExecutor();
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();
    private final Set<String> pausedJobs = ConcurrentHashMap.newKeySet();

    public StackingJobService(CameraBackendFactory backendFactory,
                              CameraProfileService profileService,
                              StackingJobRepository repository,
                              SimpMessagingTemplate messagingTemplate,
                              StackingAdvisor stackingAdvisor,
                              StackMerger stackMerger,
                              FrameDataStore frameDataStore,
                              PlainWebSocketBridge plainWebSocketBridge,
                              @Qualifier("stackingJobTaskExecutor") TaskExecutor taskExecutor,
                              @Value("${camera.focus.step-strategy:adaptive}") String stepStrategyName) {
        this.backendFactory = backendFactory;
        this.profileService = profileService;
        this.repository = repository;
        this.messagingTemplate = messagingTemplate;
        this.stackingAdvisor = stackingAdvisor;
        this.stackMerger = stackMerger;
        this.frameDataStore = frameDataStore;
        this.plainWebSocketBridge = plainWebSocketBridge;
        this.taskExecutor = taskExecutor;
        this.stepStrategy = "linear".equalsIgnoreCase(stepStrategyName)
                ? new LinearStepStrategy()
                : new AdaptiveStepStrategy();
        log.info("StackingJobService initialized with '{}' step strategy", this.stepStrategy.name());
    }

    private CameraBackend backendFor(String cameraProfileId) {
        CameraProfile profile = profileService.getProfile(cameraProfileId);
        return backendFactory.getBackend(profile);
    }

    public StackingJob createJob(String cameraProfileId, StackingParameters parameters) {
        String id = UUID.randomUUID().toString();
        StackingJob job = new StackingJob(
                id, cameraProfileId, JobStatus.CREATED,
                null, null, parameters, new ArrayList<>(), null, null);
        repository.save(StackingJobEntity.fromRecord(job));
        return job;
    }

    public StackingJob startJob(String id) {
        StackingJob job = getJob(id);
        if (job.status() != JobStatus.CREATED && job.status() != JobStatus.PAUSED) {
            throw new CameraException("Job cannot be started in state: " + job.status());
        }
        cancelledJobs.remove(id);
        pausedJobs.remove(id);
        StackingJob running = new StackingJob(
                job.id(), job.cameraProfileId(), JobStatus.RUNNING,
                Instant.now(), null, job.parameters(), new ArrayList<>(job.frames()),
                null, job.executionMetadata());
        repository.save(StackingJobEntity.fromRecord(running));
        sendJobEvent(id, "STATUS_CHANGED", Map.of("status", "RUNNING"));
        taskExecutor.execute(() -> executeStackingAsync(running));
        return running;
    }

    public void pauseJob(String id) {
        StackingJob job = getJob(id);
        if (job.status() != JobStatus.RUNNING) {
            throw new CameraException("Job cannot be paused in state: " + job.status());
        }
        pausedJobs.add(id);
        StackingJob paused = new StackingJob(
                job.id(), job.cameraProfileId(), JobStatus.PAUSED,
                job.startedAt(), null, job.parameters(), job.frames(),
                "Paused by user", job.executionMetadata());
        repository.save(StackingJobEntity.fromRecord(paused));
        sendJobEvent(id, "STATUS_CHANGED", Map.of("status", "PAUSED"));
    }

    public void cancelJob(String id) {
        StackingJob job = getJob(id);
        if (job.status() == JobStatus.COMPLETED || job.status() == JobStatus.FAILED) {
            throw new CameraException("Job cannot be cancelled in state: " + job.status());
        }
        cancelledJobs.add(id);
        StackingJob cancelled = new StackingJob(
                job.id(), job.cameraProfileId(), JobStatus.CANCELLED,
                job.startedAt(), Instant.now(), job.parameters(), job.frames(),
                "Cancelled by user", job.executionMetadata());
        repository.save(StackingJobEntity.fromRecord(cancelled));
        sendJobEvent(id, "STATUS_CHANGED", Map.of("status", "CANCELLED"));
    }

    public StackingJob getJob(String id) {
        return repository.findById(id)
                .map(StackingJobEntity::toRecord)
                .orElseThrow(() -> new CameraException("Job not found: " + id));
    }

    public List<StackingJob> getAllJobs() {
        return repository.findAllByOrderByStartedAtDesc().stream()
                .map(StackingJobEntity::toRecord)
                .toList();
    }

    public boolean deleteJob(String id) {
        return repository.findById(id).map(entity -> {
            StackingJob job = entity.toRecord();
            if (job.status() != JobStatus.COMPLETED
                    && job.status() != JobStatus.FAILED
                    && job.status() != JobStatus.CANCELLED) {
                throw new CameraException(
                        "Cannot delete job in state " + job.status()
                                + ". Only terminal jobs can be deleted.");
            }
            List<String> frameIds = job.frames();
            cancelledJobs.remove(id);
            pausedJobs.remove(id);
            repository.delete(entity);
            for (String frameId : frameIds) {
                try {
                    frameDataStore.remove(frameId);
                } catch (Exception exception) {
                    log.warn("Failed to remove frame data {}: {}",
                            frameId, exception.getMessage());
                }
            }
            log.info("Deleted stacking job {} ({} frames removed)", id, frameIds.size());
            return true;
        }).orElse(false);
    }

    void executeStackingAsync(StackingJob job) {
        CapturePlan plan = null;
        StackingJob plannedJob = job;
        try {
            CameraBackend backend = backendFor(job.cameraProfileId());
            plan = selectCapturePlan(job.parameters(), backend);
            plannedJob = withExecutionMetadata(
                    job, JobStatus.RUNNING, null, null,
                    job.frames(), plan.metadata());
            repository.save(StackingJobEntity.fromRecord(plannedJob));
            sendJobEvent(job.id(), "CAPTURE_STRATEGY_SELECTED", Map.of(
                    "requested", plan.metadata().requestedStrategy().name(),
                    "selected", plan.metadata().selectedStrategy().name()));
            sendJobLog(job.id(), "INFO", "Selected "
                    + plan.metadata().selectedStrategy() + " capture strategy");

            if (plan.metadata().selectedStrategy() == StackingCaptureStrategy.NATIVE) {
                executeNativeStacking(plannedJob, backend, plan);
            } else {
                executeManualStacking(plannedJob, backend, plan.metadata());
            }
        } catch (Exception exception) {
            StackingExecutionMetadata metadata = plan == null
                    ? plannedJob.executionMetadata() : plan.metadata();
            failJob(plannedJob, new ArrayList<>(plannedJob.frames()), metadata, exception);
        }
    }

    private CapturePlan selectCapturePlan(
            StackingParameters parameters,
            CameraBackend backend) {
        StackingCaptureStrategy requested = parameters.captureStrategy();
        NativeFocusBracketingRequest nativeRequest = parameters.nativeFocusBracketing();
        boolean nativeExecutable = false;
        String nativeMismatch = null;

        if (backend instanceof NativeFocusBracketingExecutionProvider nativeProvider
                && backend instanceof CameraMediaProvider
                && nativeRequest != null) {
            List<String> errors = nativeProvider.getNativeFocusBracketingCapabilities()
                    .validate(nativeRequest);
            nativeExecutable = errors.isEmpty();
            nativeMismatch = errors.isEmpty() ? null : String.join("; ", errors);
        } else if (requested != StackingCaptureStrategy.MANUAL) {
            nativeMismatch = "backend does not expose native execution and media transport";
        }

        StackingCaptureStrategy selected = switch (requested) {
            case MANUAL -> StackingCaptureStrategy.MANUAL;
            case NATIVE -> {
                if (!nativeExecutable) {
                    throw new CameraException(
                            "Native focus bracketing is unavailable: " + nativeMismatch);
                }
                yield StackingCaptureStrategy.NATIVE;
            }
            case AUTO -> nativeExecutable
                    ? StackingCaptureStrategy.NATIVE
                    : StackingCaptureStrategy.MANUAL;
        };

        int frames = selected == StackingCaptureStrategy.NATIVE
                ? nativeRequest.numberOfShots() : parameters.maxFrames();
        int increment = selected == StackingCaptureStrategy.NATIVE
                ? nativeRequest.focusIncrement() : parameters.stepSize();
        StackingExecutionMetadata metadata = new StackingExecutionMetadata(
                requested,
                selected,
                frames,
                increment,
                selected == StackingCaptureStrategy.NATIVE
                        && nativeRequest.exposureSmoothing(),
                selected == StackingCaptureStrategy.NATIVE
                        && nativeRequest.depthComposition(),
                selected == StackingCaptureStrategy.NATIVE
                        && nativeRequest.cropDepthComposition(),
                null, 0, 0, null,
                requested == StackingCaptureStrategy.AUTO && !nativeExecutable
                        ? "AUTO selected manual fallback: " + nativeMismatch
                        : null);
        return new CapturePlan(metadata, nativeRequest);
    }

    private void executeNativeStacking(
            StackingJob job,
            CameraBackend backend,
            CapturePlan plan) {
        String id = job.id();
        NativeFocusBracketingRequest request = plan.nativeRequest();
        NativeFocusBracketingExecutionResult result = nativeExecutor.execute(
                backend,
                request,
                nativeTimeout(request),
                () -> cancelledJobs.contains(id) || pausedJobs.contains(id),
                this::ingestNativeFrame);

        List<String> frames = new ArrayList<>(job.frames());
        frames.addAll(result.ingestedFrameIds());
        StackingExecutionMetadata metadata = plan.metadata().withNativeResult(result);
        publishNativeFrames(id, result.ingestedFrameIds(), request.numberOfShots());

        JobStatus status;
        String stopReason;
        if (pausedJobs.contains(id)) {
            status = JobStatus.PAUSED;
            stopReason = "Paused by user";
        } else if (cancelledJobs.contains(id)
                || result.completionState()
                == NativeFocusBracketingExecutionResult.CompletionState.CANCELLED) {
            status = JobStatus.CANCELLED;
            stopReason = "Cancelled by user";
        } else if (result.completionState()
                == NativeFocusBracketingExecutionResult.CompletionState.COMPLETE) {
            status = JobStatus.COMPLETED;
            stopReason = "Native sequence completed";
        } else {
            status = JobStatus.FAILED;
            stopReason = result.partialFailureReason() == null
                    ? "Native sequence did not complete"
                    : result.partialFailureReason();
        }

        Instant completedAt = switch (status) {
            case COMPLETED, FAILED, CANCELLED -> Instant.now();
            default -> null;
        };
        StackingJob terminal = withExecutionMetadata(
                job, status, completedAt, stopReason, frames, metadata);
        repository.save(StackingJobEntity.fromRecord(terminal));

        if (status == JobStatus.COMPLETED) {
            sendJobEvent(id, "SEQUENCE_COMPLETED", Map.of(
                    "strategy", "NATIVE",
                    "totalFrames", frames.size(),
                    "observedMedia", result.observedMedia().size()));
            sendJobLog(id, "INFO", "Native sequence completed with "
                    + result.ingestedFrameIds().size() + " ingested frames");
        } else if (status == JobStatus.PAUSED || status == JobStatus.CANCELLED) {
            sendJobEvent(id, "STATUS_CHANGED", Map.of("status", status.name()));
            sendJobLog(id, "INFO", stopReason + " after preserving "
                    + result.ingestedFrameIds().size() + " native frames");
        } else {
            sendJobEvent(id, "ERROR", Map.of("message", stopReason));
            sendJobLog(id, "ERROR", "Native sequence failed after preserving "
                    + result.ingestedFrameIds().size() + " frames: " + stopReason);
        }
    }

    private String ingestNativeFrame(
            CameraMediaProvider mediaProvider,
            CameraMediaReference reference) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long offset = 0L;
        int chunks = 0;
        while (true) {
            if (++chunks > 128) {
                throw new CameraException("Native media exceeded the bounded chunk count");
            }
            CameraMediaProvider.MediaChunk chunk = mediaProvider.downloadMedia(
                    reference, offset, NATIVE_DOWNLOAD_CHUNK_BYTES);
            if (chunk.offset() != offset) {
                throw new CameraException("Native media returned a non-contiguous chunk");
            }
            byte[] data = chunk.data();
            if (data.length == 0 && !chunk.complete()) {
                throw new CameraException("Native media transfer made no progress");
            }
            if (output.size() + (long) data.length > MAX_NATIVE_FRAME_BYTES
                    || chunk.totalSize() != null
                    && chunk.totalSize() > MAX_NATIVE_FRAME_BYTES) {
                throw new CameraException("Native media exceeds the 1 GiB safety limit");
            }
            output.writeBytes(data);
            offset += data.length;
            if (chunk.complete()) {
                break;
            }
        }

        String frameId = UUID.randomUUID().toString();
        frameDataStore.put(frameId, reference.fileName(), output.toByteArray());
        return frameId;
    }

    private void publishNativeFrames(
            String jobId,
            List<String> frameIds,
            int expectedFrames) {
        List<byte[]> captured = new ArrayList<>();
        for (int index = 0; index < frameIds.size(); index++) {
            String frameId = frameIds.get(index);
            FrameDataStore.FrameEntry entry = frameDataStore.getEntry(frameId);
            byte[] data = entry == null ? null : entry.data();
            if (data != null && data.length > 0) {
                captured.add(data);
            }
            sendPreviewFrame(jobId, data, index);
            sendStackPreview(jobId, captured, index);
            sendJobEvent(jobId, "FRAME_CAPTURED", Map.of(
                    "strategy", "NATIVE",
                    "frameIndex", index,
                    "totalFrames", expectedFrames,
                    "imageId", frameId));
        }
    }

    private void executeManualStacking(
            StackingJob job,
            CameraBackend cameraBackend,
            StackingExecutionMetadata metadata) {
        String id = job.id();
        List<String> frames = new ArrayList<>(job.frames());
        List<AnalysisResult> analysisHistory = new ArrayList<>();
        List<byte[]> capturedFrameData = new ArrayList<>();
        StackingParameters params = job.parameters();
        byte[] previousFrameData = null;

        cameraBackend.setFocus(
                new FocusCommand(FocusCommand.Direction.FAR, params.startPosition()));
        sendJobLog(id, "INFO", "Starting capture at focus position " + params.startPosition());
        log.info("Job {}: starting manual capture with '{}' step strategy",
                id, stepStrategy.name());

        for (int i = 0; i < params.maxFrames(); i++) {
            if (cancelledJobs.contains(id)) {
                sendJobLog(id, "INFO", "Job cancelled at frame " + i);
                return;
            }
            if (pausedJobs.contains(id)) {
                sendJobLog(id, "INFO", "Job paused at frame " + i);
                return;
            }

            CaptureResult result = cameraBackend.capture();
            frames.add(result.imageId());
            if (result.imageData() != null && result.imageData().length > 0) {
                frameDataStore.put(result.imageId(), result.fileName(), result.imageData());
                capturedFrameData.add(result.imageData());
                log.debug("Job {}: frame {} stored ({} bytes, file={})",
                        id, result.imageId(), result.imageData().length, result.fileName());
            } else {
                log.warn("Job {}: frame {} has no image data", id, result.imageId());
                sendJobLog(id, "WARN", "Frame " + (i + 1)
                        + " captured but image data is missing");
            }

            sendPreviewFrame(id, result.imageData(), i);
            sendStackPreview(id, capturedFrameData, i);

            AnalysisResult analysisResult = null;
            if (result.imageData() != null) {
                try {
                    analysisResult = stackingAdvisor.analyzeFrame(
                            result.imageData(), previousFrameData,
                            analysisHistory, params);
                } catch (Exception analysisException) {
                    log.warn("Job {}: frame analysis failed for frame {} — continuing. {}",
                            id, i, analysisException.getMessage());
                    sendJobLog(id, "WARN", "Frame " + (i + 1)
                            + " analysis failed — continuing capture");
                }
            }
            previousFrameData = result.imageData();

            Map<String, Object> frameEvent = new HashMap<>();
            frameEvent.put("strategy", "MANUAL");
            frameEvent.put("frameIndex", i);
            frameEvent.put("totalFrames", params.maxFrames());
            frameEvent.put("imageId", result.imageId());
            frameEvent.put("focusPosition", result.focusPosition());
            if (analysisResult != null) {
                frameEvent.put("sharpnessScore", analysisResult.sharpnessScore());
                frameEvent.put("motionDetected", analysisResult.motionDetected());
                frameEvent.put("recommendContinue", analysisResult.recommendContinue());
                frameEvent.put("newInformationGain", analysisResult.newInformationGain());
                analysisHistory.add(analysisResult);
            }
            sendJobEvent(id, "FRAME_CAPTURED", frameEvent);

            if (analysisResult != null && !analysisResult.recommendContinue()) {
                sendJobLog(id, "INFO", "Analysis recommends stopping after frame "
                        + (i + 1));
                break;
            }

            int nextStep = stepStrategy.nextStepSize(analysisHistory, params.stepSize());
            cameraBackend.setFocus(
                    new FocusCommand(FocusCommand.Direction.FAR, nextStep));
            StackingJob updated = withExecutionMetadata(
                    job, JobStatus.RUNNING, null, null,
                    new ArrayList<>(frames), metadata);
            repository.save(StackingJobEntity.fromRecord(updated));
        }

        String stopReason = frames.size() < params.maxFrames()
                ? "Analysis recommended early stop" : "Captured all frames";
        StackingJob completed = withExecutionMetadata(
                job, JobStatus.COMPLETED, Instant.now(), stopReason, frames, metadata);
        repository.save(StackingJobEntity.fromRecord(completed));
        sendJobEvent(id, "SEQUENCE_COMPLETED", Map.of(
                "strategy", "MANUAL", "totalFrames", frames.size()));
        sendJobLog(id, "INFO", "Job completed with " + frames.size() + " frames");
    }

    private void failJob(
            StackingJob job,
            List<String> frames,
            StackingExecutionMetadata metadata,
            Exception exception) {
        String id = job.id();
        String message = safeFailure(exception);
        log.error("Stacking job {} failed: {}", id, message, exception);
        StackingJob failed = withExecutionMetadata(
                job, JobStatus.FAILED, Instant.now(), message, frames, metadata);
        repository.save(StackingJobEntity.fromRecord(failed));
        sendJobEvent(id, "ERROR", Map.of("message", message));
        sendJobLog(id, "ERROR", "Job failed: " + message);
    }

    private static StackingJob withExecutionMetadata(
            StackingJob source,
            JobStatus status,
            Instant completedAt,
            String stopReason,
            List<String> frames,
            StackingExecutionMetadata metadata) {
        return new StackingJob(
                source.id(), source.cameraProfileId(), status,
                source.startedAt(), completedAt, source.parameters(),
                frames, stopReason, metadata);
    }

    private static Duration nativeTimeout(NativeFocusBracketingRequest request) {
        long seconds = Math.max(30L, Math.min(600L, request.numberOfShots() * 5L));
        return Duration.ofSeconds(seconds);
    }

    private void sendPreviewFrame(String jobId, byte[] imageData, int frameIndex) {
        if (imageData == null || imageData.length == 0) return;
        try {
            String base64 = Base64.getEncoder().encodeToString(imageData);
            Map<String, Object> preview = Map.of(
                    "type", "camera",
                    "frameIndex", frameIndex,
                    "imageBase64", base64,
                    "timestamp", Instant.now().toString(),
                    "jobId", jobId);
            messagingTemplate.convertAndSend(
                    "/topic/jobs/" + jobId + "/preview", (Object) preview);
            plainWebSocketBridge.broadcast(preview);
        } catch (Exception exception) {
            log.debug("Failed to send camera preview for job {}: {}",
                    jobId, exception.getMessage());
        }
    }

    private void sendStackPreview(String jobId, List<byte[]> capturedFrameData, int frameIndex) {
        if (capturedFrameData.size() < 2) return;
        try {
            List<byte[]> framesToMerge;
            if (capturedFrameData.size() <= MAX_PREVIEW_MERGE_FRAMES) {
                framesToMerge = capturedFrameData;
            } else {
                framesToMerge = new ArrayList<>();
                double step = (double) capturedFrameData.size() / MAX_PREVIEW_MERGE_FRAMES;
                for (int i = 0; i < MAX_PREVIEW_MERGE_FRAMES; i++) {
                    framesToMerge.add(capturedFrameData.get((int) (i * step)));
                }
                framesToMerge.add(capturedFrameData.get(capturedFrameData.size() - 1));
            }
            byte[] merged = stackMerger.merge(framesToMerge);
            if (merged != null && merged.length > 0) {
                String base64 = Base64.getEncoder().encodeToString(merged);
                Map<String, Object> preview = Map.of(
                        "type", "stack",
                        "frameIndex", frameIndex,
                        "framesUsed", framesToMerge.size(),
                        "imageBase64", base64,
                        "timestamp", Instant.now().toString(),
                        "jobId", jobId);
                messagingTemplate.convertAndSend(
                        "/topic/jobs/" + jobId + "/preview", (Object) preview);
                plainWebSocketBridge.broadcast(preview);
            }
        } catch (Exception exception) {
            log.debug("Failed to send stack preview for job {}: {}",
                    jobId, exception.getMessage());
        }
    }

    private void sendJobEvent(String jobId, String event, Map<String, Object> data) {
        Map<String, Object> message = new HashMap<>(data);
        message.put("event", event);
        message.put("jobId", jobId);
        message.put("timestamp", Instant.now().toString());
        messagingTemplate.convertAndSend("/topic/jobs/" + jobId + "/progress", (Object) message);
        plainWebSocketBridge.broadcast(message);
    }

    private void sendJobLog(String jobId, String level, String text) {
        Map<String, Object> message = Map.of(
                "level", level,
                "message", text,
                "jobId", jobId,
                "timestamp", Instant.now().toString());
        messagingTemplate.convertAndSend("/topic/jobs/" + jobId + "/logs", (Object) message);
        plainWebSocketBridge.broadcast(message);
    }

    private static String safeFailure(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private record CapturePlan(
            StackingExecutionMetadata metadata,
            NativeFocusBracketingRequest nativeRequest) {
    }
}
