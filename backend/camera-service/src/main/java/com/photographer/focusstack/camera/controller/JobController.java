package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.search.SearchService;
import com.photographer.focusstack.camera.service.StackingJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final StackingJobService jobService;
    private final SearchService searchService;

    public JobController(StackingJobService jobService, SearchService searchService) {
        this.jobService = jobService;
        this.searchService = searchService;
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
