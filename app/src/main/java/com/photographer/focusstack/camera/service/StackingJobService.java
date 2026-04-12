package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.analysis.service.StackMerger;
import com.photographer.focusstack.analysis.service.StackingAdvisor;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.repository.StackingJobRepository;
import com.photographer.focusstack.camera.strategy.AdaptiveStepStrategy;
import com.photographer.focusstack.camera.strategy.FocusStepStrategy;
import com.photographer.focusstack.camera.strategy.LinearStepStrategy;
import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import com.photographer.focusstack.camera.exception.CameraException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

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

    /** Maximum number of frames to include in a progressive stack merge preview. */
    private static final int MAX_PREVIEW_MERGE_FRAMES = 20;

    private final CameraBackendFactory backendFactory;
    private final CameraProfileService profileService;
    private final StackingJobRepository repository;
    private final SimpMessagingTemplate messagingTemplate;
    private final StackingAdvisor stackingAdvisor;
    private final StackMerger stackMerger;
    private final FocusStepStrategy stepStrategy;
    private final TaskExecutor taskExecutor;
    private final FrameDataStore frameDataStore;
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();
    private final Set<String> pausedJobs = ConcurrentHashMap.newKeySet();

    public StackingJobService(CameraBackendFactory backendFactory,
                              CameraProfileService profileService,
                              StackingJobRepository repository,
                              SimpMessagingTemplate messagingTemplate,
                              StackingAdvisor stackingAdvisor,
                              StackMerger stackMerger,
                              FrameDataStore frameDataStore,
                              @Qualifier("stackingJobTaskExecutor") TaskExecutor taskExecutor,
                              @Value("${camera.focus.step-strategy:adaptive}") String stepStrategyName) {
        this.backendFactory = backendFactory;
        this.profileService = profileService;
        this.repository = repository;
        this.messagingTemplate = messagingTemplate;
        this.stackingAdvisor = stackingAdvisor;
        this.stackMerger = stackMerger;
        this.frameDataStore = frameDataStore;
        this.taskExecutor = taskExecutor;
        this.stepStrategy = "linear".equalsIgnoreCase(stepStrategyName)
                ? new LinearStepStrategy()
                : new AdaptiveStepStrategy();
        log.info("StackingJobService initialized with '{}' step strategy", this.stepStrategy.name());
    }

    /** Resolve the correct backend for the given camera profile id. */
    private CameraBackend backendFor(String cameraProfileId) {
        CameraProfile profile = profileService.getProfile(cameraProfileId);
        return backendFactory.getBackend(profile);
    }

    public StackingJob createJob(String cameraProfileId, StackingParameters parameters) {
        String id = UUID.randomUUID().toString();
        StackingJob job = new StackingJob(
                id, cameraProfileId, JobStatus.CREATED,
                null, null, parameters, new ArrayList<>()
        );
        repository.save(StackingJobEntity.fromRecord(job));
        return job;
    }

    /**
     * Validates and transitions the job to RUNNING synchronously, then delegates
     * the capture loop to an async method. This ensures validation errors (not
     * found, invalid state) propagate to the caller before returning 202.
     */
    public StackingJob startJob(String id) {
        StackingJob job = getJob(id);
        if (job.status() != JobStatus.CREATED && job.status() != JobStatus.PAUSED) {
            throw new CameraException("Job cannot be started in state: " + job.status());
        }

        cancelledJobs.remove(id);
        pausedJobs.remove(id);

        StackingJob running = new StackingJob(
                job.id(), job.cameraProfileId(), JobStatus.RUNNING,
                Instant.now(), null, job.parameters(), new ArrayList<>(job.frames())
        );
        repository.save(StackingJobEntity.fromRecord(running));
        sendJobEvent(id, "STATUS_CHANGED", Map.of("status", "RUNNING"));

        // Submit the capture loop to the task executor so it runs on a Spring-managed
        // thread pool. This avoids the @Async self-invocation proxy issue while still
        // keeping the request thread free.
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
                job.startedAt(), null, job.parameters(), job.frames()
        );
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
                job.startedAt(), Instant.now(), job.parameters(), job.frames()
        );
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

    void executeStackingAsync(StackingJob job) {
        String id = job.id();
        CameraBackend cameraBackend = backendFor(job.cameraProfileId());
        List<String> frames = new ArrayList<>(job.frames());
        List<AnalysisResult> analysisHistory = new ArrayList<>();
        List<byte[]> capturedFrameData = new ArrayList<>();
        StackingParameters params = job.parameters();
        byte[] previousFrameData = null;

        try {
            cameraBackend.setFocus(
                    new FocusCommand(FocusCommand.Direction.FAR, params.startPosition()));
            sendJobLog(id, "INFO", "Starting capture at focus position " + params.startPosition());
            log.info("Job {}: starting with '{}' step strategy", id, stepStrategy.name());

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

                // Cache frame data for later export and image display
                if (result.imageData() != null && result.imageData().length > 0) {
                    frameDataStore.put(result.imageId(), result.fileName(), result.imageData());
                    capturedFrameData.add(result.imageData());
                    log.debug("Job {}: frame {} stored ({} bytes, file={})",
                            id, result.imageId(), result.imageData().length, result.fileName());
                } else {
                    log.warn("Job {}: frame {} has NO image data (imageData={}). "
                                    + "The image will not be available for display or export.",
                            id, result.imageId(),
                            result.imageData() == null ? "null" : "empty");
                    sendJobLog(id, "WARN", "Frame " + (i + 1) + " captured but image data "
                            + "is missing — the camera may have timed out");
                }

                // --- Send camera preview frame ---
                sendPreviewFrame(id, result.imageData(), i);

                // --- Progressive stack merge preview ---
                sendStackPreview(id, capturedFrameData, i);

                // --- Analysis phase ---
                AnalysisResult analysisResult = null;
                if (result.imageData() != null) {
                    try {
                        analysisResult = stackingAdvisor
                                .analyzeFrame(result.imageData(), previousFrameData,
                                        analysisHistory, params);
                    } catch (Exception analysisEx) {
                        log.warn("Job {}: frame analysis failed for frame {} — continuing capture. {}",
                                id, i, analysisEx.getMessage());
                        sendJobLog(id, "WARN", "Frame " + (i + 1) + " analysis failed: "
                                + analysisEx.getMessage() + " — continuing capture");
                    }
                }
                previousFrameData = result.imageData();

                Map<String, Object> frameEvent = new HashMap<>();
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
                sendJobLog(id, "DEBUG", String.format(
                        "Captured frame %d/%d at focus position %d%s",
                        i + 1, params.maxFrames(), result.focusPosition(),
                        analysisResult != null
                                ? String.format(" (sharpness=%.3f, gain=%.3f, motion=%.3f, continue=%b)",
                                        analysisResult.sharpnessScore(),
                                        analysisResult.newInformationGain(),
                                        analysisResult.motionScore(),
                                        analysisResult.recommendContinue())
                                : " (no analysis — imageData missing)"));

                // --- Early-stop check ---
                if (analysisResult != null && !analysisResult.recommendContinue()) {
                    sendJobLog(id, "INFO", String.format(
                            "Analysis recommends stopping after frame %d (motion=%b, gain=%.3f)",
                            i + 1, analysisResult.motionDetected(), analysisResult.newInformationGain()));
                    log.info("Job {}: early stop after frame {} on analysis recommendation", id, i);
                    break;
                }

                // --- Adaptive step computation ---
                int nextStep = stepStrategy.nextStepSize(analysisHistory, params.stepSize());
                log.debug("Job {}: captured frame {} at focus position {}, next step={}",
                        id, i, result.focusPosition(), nextStep);
                cameraBackend.setFocus(
                        new FocusCommand(FocusCommand.Direction.FAR, nextStep));

                StackingJob updated = new StackingJob(
                        id, job.cameraProfileId(), JobStatus.RUNNING,
                        job.startedAt(), null, params, new ArrayList<>(frames)
                );
                repository.save(StackingJobEntity.fromRecord(updated));
            }

            StackingJob completed = new StackingJob(
                    id, job.cameraProfileId(), JobStatus.COMPLETED,
                    job.startedAt(), Instant.now(), params, frames
            );
            repository.save(StackingJobEntity.fromRecord(completed));
            sendJobEvent(id, "SEQUENCE_COMPLETED", Map.of("totalFrames", frames.size()));
            sendJobLog(id, "INFO", "Job completed with " + frames.size() + " frames");
            log.info("Stacking job {} completed with {} frames", id, frames.size());

        } catch (Exception e) {
            log.error("Stacking job {} failed: {}", id, e.getMessage(), e);
            StackingJob failed = new StackingJob(
                    id, job.cameraProfileId(), JobStatus.FAILED,
                    job.startedAt(), Instant.now(), params, frames
            );
            repository.save(StackingJobEntity.fromRecord(failed));
            sendJobEvent(id, "ERROR", Map.of("message", e.getMessage()));
            sendJobLog(id, "ERROR", "Job failed: " + e.getMessage());
        }
    }

    /**
     * Sends the latest camera frame as a base64-encoded preview via WebSocket.
     */
    private void sendPreviewFrame(String jobId, byte[] imageData, int frameIndex) {
        if (imageData == null || imageData.length == 0) return;
        try {
            String base64 = Base64.getEncoder().encodeToString(imageData);
            Map<String, Object> preview = Map.of(
                    "type", "camera",
                    "frameIndex", frameIndex,
                    "imageBase64", base64,
                    "timestamp", Instant.now().toString()
            );
            messagingTemplate.convertAndSend(
                    "/topic/jobs/" + jobId + "/preview", (Object) preview);
        } catch (Exception e) {
            log.debug("Failed to send camera preview for job {}: {}", jobId, e.getMessage());
        }
    }

    /**
     * Produces a progressive stack-merged preview from captured frames so far
     * and sends it as a base64-encoded image via WebSocket.
     * Uses at most {@value MAX_PREVIEW_MERGE_FRAMES} evenly-spaced frames for performance.
     */
    private void sendStackPreview(String jobId, List<byte[]> capturedFrameData, int frameIndex) {
        if (capturedFrameData.size() < 2) return;
        try {
            // Sample frames evenly if there are too many for quick merging
            List<byte[]> framesToMerge;
            if (capturedFrameData.size() <= MAX_PREVIEW_MERGE_FRAMES) {
                framesToMerge = capturedFrameData;
            } else {
                framesToMerge = new ArrayList<>();
                double step = (double) capturedFrameData.size() / MAX_PREVIEW_MERGE_FRAMES;
                for (int i = 0; i < MAX_PREVIEW_MERGE_FRAMES; i++) {
                    framesToMerge.add(capturedFrameData.get((int) (i * step)));
                }
                // Always include the latest frame
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
                        "timestamp", Instant.now().toString()
                );
                messagingTemplate.convertAndSend(
                        "/topic/jobs/" + jobId + "/preview", (Object) preview);
            }
        } catch (Exception e) {
            log.debug("Failed to send stack preview for job {}: {}", jobId, e.getMessage());
        }
    }

    private void sendJobEvent(String jobId, String event, Map<String, Object> data) {
        Map<String, Object> message = new HashMap<>(data);
        message.put("event", event);
        message.put("jobId", jobId);
        message.put("timestamp", Instant.now().toString());
        messagingTemplate.convertAndSend("/topic/jobs/" + jobId + "/progress", (Object) message);
    }

    private void sendJobLog(String jobId, String level, String text) {
        Map<String, Object> message = Map.of(
                "level", level,
                "message", text,
                "jobId", jobId,
                "timestamp", Instant.now().toString()
        );
        messagingTemplate.convertAndSend("/topic/jobs/" + jobId + "/logs", (Object) message);
    }
}