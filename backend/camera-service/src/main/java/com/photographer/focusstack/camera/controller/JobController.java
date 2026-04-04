package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.search.SearchService;
import com.photographer.focusstack.camera.service.FrameDataStore;
import com.photographer.focusstack.camera.service.StackingJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final StackingJobService jobService;
    private final SearchService searchService;
    private final FrameDataStore frameDataStore;

    public JobController(StackingJobService jobService,
                         SearchService searchService,
                         FrameDataStore frameDataStore) {
        this.jobService = jobService;
        this.searchService = searchService;
        this.frameDataStore = frameDataStore;
    }

    @GetMapping
    public ResponseEntity<List<StackingJob>> listJobs() {
        return ResponseEntity.ok(jobService.getAllJobs());
    }

    @PostMapping
    public ResponseEntity<StackingJob> createJob(@Valid @RequestBody CreateJobRequest request) {
        StackingJob job = jobService.createJob(request.cameraProfileId(), request.parameters());
        return ResponseEntity.status(HttpStatus.CREATED).body(job);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StackingJob> getJob(@PathVariable String id) {
        return ResponseEntity.ok(jobService.getJob(id));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<StackingJob> startJob(@PathVariable String id) {
        StackingJob running = jobService.startJob(id);
        return ResponseEntity.accepted().body(running);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pauseJob(@PathVariable String id) {
        jobService.pauseJob(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelJob(@PathVariable String id) {
        jobService.cancelJob(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Exports all captured frames for a job as a ZIP archive.
     *
     * <p>Only frames whose raw data was cached in {@link FrameDataStore} during
     * capture are included.  Frames that were not cached (e.g. from the real CCAPI
     * backend which stores images on the camera card) are listed as empty entries.
     *
     * @param id the job id
     * @return a ZIP file containing one PNG per frame, or 404 if the job is not found
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportFrames(@PathVariable String id) throws IOException {
        StackingJob job = jobService.getJob(id);
        List<String> frameIds = job.frames();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            int index = 1;
            for (String frameId : frameIds) {
                String entryName = String.format("frame_%03d_%s.png", index++, frameId);
                byte[] data = frameDataStore.get(frameId);
                zip.putNextEntry(new ZipEntry(entryName));
                if (data != null) {
                    zip.write(data);
                }
                zip.closeEntry();
            }
        }

        byte[] zipBytes = buffer.toByteArray();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"job-" + id + "-frames.zip\"")
                .body(zipBytes);
    }

    @GetMapping("/search")
    public ResponseEntity<List<StackingJob>> searchJobs(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int maxResults) {
        List<StackingJob> results = searchService.searchStackingJobs(q, maxResults)
                .stream()
                .map(StackingJobEntity::toRecord)
                .toList();
        return ResponseEntity.ok(results);
    }

    @PostMapping("/reindex")
    public ResponseEntity<Void> reindex() {
        searchService.reindexAll();
        return ResponseEntity.ok().build();
    }

    public record CreateJobRequest(
            @NotBlank String cameraProfileId,
            @Valid StackingParameters parameters
    ) {}
}
