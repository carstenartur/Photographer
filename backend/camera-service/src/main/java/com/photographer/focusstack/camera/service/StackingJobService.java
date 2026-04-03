package com.photographer.focusstack.camera.service;

import com.canon.focusstack.common.camera.CameraBackend;
import com.canon.focusstack.common.camera.CaptureResult;
import com.canon.focusstack.common.camera.FocusCommand;
import com.canon.focusstack.common.model.JobStatus;
import com.canon.focusstack.common.model.StackingJob;
import com.canon.focusstack.common.model.StackingParameters;
import com.photographer.focusstack.camera.exception.CameraException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StackingJobService {

    private static final Logger log = LoggerFactory.getLogger(StackingJobService.class);

    private final CameraBackend cameraBackend;
    private final ConcurrentHashMap<String, StackingJob> jobs = new ConcurrentHashMap<>();
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();
    private final Set<String> pausedJobs = ConcurrentHashMap.newKeySet();

    public StackingJobService(CameraBackend cameraBackend) {
        this.cameraBackend = cameraBackend;
    }

    public StackingJob createJob(String cameraProfileId, StackingParameters parameters) {
        String id = UUID.randomUUID().toString();
        StackingJob job = new StackingJob(
                id, cameraProfileId, JobStatus.CREATED,
                null, null, parameters, new ArrayList<>()
        );
        jobs.put(id, job);
        return job;
    }

    @Async
    public void startJob(String id) {
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
        jobs.put(id, running);

        executeStacking(running);
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
        jobs.put(id, paused);
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
        jobs.put(id, cancelled);
    }

    public StackingJob getJob(String id) {
        StackingJob job = jobs.get(id);
        if (job == null) {
            throw new CameraException("Job not found: " + id);
        }
        return job;
    }

    public List<StackingJob> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    private void executeStacking(StackingJob job) {
        String id = job.id();
        List<String> frames = new ArrayList<>(job.frames());
        StackingParameters params = job.parameters();

        try {
            cameraBackend.setFocus(
                    new FocusCommand(FocusCommand.Direction.FAR, params.startPosition()));

            for (int i = 0; i < params.maxFrames(); i++) {
                if (cancelledJobs.contains(id) || pausedJobs.contains(id)) {
                    log.info("Stacking job {} interrupted at frame {}", id, i);
                    return;
                }

                CaptureResult result = cameraBackend.capture();
                frames.add(result.imageId());

                cameraBackend.setFocus(
                        new FocusCommand(FocusCommand.Direction.FAR, params.stepSize()));

                StackingJob updated = new StackingJob(
                        id, job.cameraProfileId(), JobStatus.RUNNING,
                        job.startedAt(), null, params, new ArrayList<>(frames)
                );
                jobs.put(id, updated);

                log.debug("Job {}: captured frame {} at focus position {}",
                        id, i, result.focusPosition());
            }

            StackingJob completed = new StackingJob(
                    id, job.cameraProfileId(), JobStatus.COMPLETED,
                    job.startedAt(), Instant.now(), params, frames
            );
            jobs.put(id, completed);
            log.info("Stacking job {} completed with {} frames", id, frames.size());

        } catch (Exception e) {
            log.error("Stacking job {} failed: {}", id, e.getMessage(), e);
            StackingJob failed = new StackingJob(
                    id, job.cameraProfileId(), JobStatus.FAILED,
                    job.startedAt(), Instant.now(), params, frames
            );
            jobs.put(id, failed);
        }
    }
}
