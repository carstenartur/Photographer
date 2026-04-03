package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.repository.StackingJobRepository;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import com.photographer.focusstack.camera.exception.CameraException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StackingJobService {

    private static final Logger log = LoggerFactory.getLogger(StackingJobService.class);

    private final CameraBackend cameraBackend;
    private final StackingJobRepository repository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();
    private final Set<String> pausedJobs = ConcurrentHashMap.newKeySet();

    public StackingJobService(CameraBackend cameraBackend, StackingJobRepository repository,
                              SimpMessagingTemplate messagingTemplate) {
        this.cameraBackend = cameraBackend;
        this.repository = repository;
        this.messagingTemplate = messagingTemplate;
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

        executeStackingAsync(running);
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
        return repository.findAll().stream()
                .map(StackingJobEntity::toRecord)
                .toList();
    }

    @Async
    void executeStackingAsync(StackingJob job) {
        String id = job.id();
        List<String> frames = new ArrayList<>(job.frames());
        StackingParameters params = job.parameters();

        try {
            cameraBackend.setFocus(
                    new FocusCommand(FocusCommand.Direction.FAR, params.startPosition()));
            sendJobLog(id, "INFO", "Starting capture at focus position " + params.startPosition());

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

                sendJobEvent(id, "FRAME_CAPTURED", Map.of(
                        "frameIndex", i,
                        "totalFrames", params.maxFrames(),
                        "imageId", result.imageId(),
                        "focusPosition", result.focusPosition()
                ));
                sendJobLog(id, "DEBUG", String.format(
                        "Captured frame %d/%d at focus position %d",
                        i + 1, params.maxFrames(), result.focusPosition()));

                cameraBackend.setFocus(
                        new FocusCommand(FocusCommand.Direction.FAR, params.stepSize()));

                StackingJob updated = new StackingJob(
                        id, job.cameraProfileId(), JobStatus.RUNNING,
                        job.startedAt(), null, params, new ArrayList<>(frames)
                );
                repository.save(StackingJobEntity.fromRecord(updated));

                log.debug("Job {}: captured frame {} at focus position {}",
                        id, i, result.focusPosition());
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

    private void sendJobEvent(String jobId, String event, Map<String, Object> data) {
        Map<String, Object> message = new HashMap<>(data);
        message.put("event", event);
        message.put("jobId", jobId);
        message.put("timestamp", Instant.now().toString());
        messagingTemplate.convertAndSend("/topic/jobs/" + jobId + "/progress", message);
    }

    private void sendJobLog(String jobId, String level, String text) {
        Map<String, Object> message = Map.of(
                "level", level,
                "message", text,
                "jobId", jobId,
                "timestamp", Instant.now().toString()
        );
        messagingTemplate.convertAndSend("/topic/jobs/" + jobId + "/logs", message);
    }
}
