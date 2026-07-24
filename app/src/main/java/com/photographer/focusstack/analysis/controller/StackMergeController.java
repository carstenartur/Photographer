package com.photographer.focusstack.analysis.controller;

import com.photographer.focusstack.app.api.ApiPaths;
import com.photographer.focusstack.analysis.service.StackMerger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for merging a focus-stack sequence into a single all-in-focus image.
 *
 * <p>Accepts a multipart upload of 2–100 frames and returns the merged PNG image,
 * or delegates to the configured {@link StackMerger} strategy.
 *
 * <p>When a {@code jobId} is supplied the merge result is cached in memory so it
 * can later be retrieved via {@code GET /api/analysis/merge-result/{jobId}}.
 */
@RestController
@RequestMapping({ApiPaths.V1 + "/analysis", ApiPaths.LEGACY + "/analysis"})
@Tag(name = "Stack Merge", description = "Focus-stack merge and merge-result retrieval endpoints.")
public class StackMergeController {

    private final StackMerger stackMerger;

    /** Maximum number of merge results to keep in memory (least recently accessed is evicted). */
    private static final int MAX_CACHED_RESULTS = 50;

    /** In-memory LRU cache: jobId → merged PNG bytes. Evicts the least recently accessed entry when full. */
    @SuppressWarnings("serial")
    private final Map<String, byte[]> mergeResultCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(MAX_CACHED_RESULTS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_CACHED_RESULTS;
                }
            });

    public StackMergeController(StackMerger stackMerger) {
        this.stackMerger = stackMerger;
    }

    /**
     * Merges the supplied frames into a single all-in-focus image using EDF.
     *
     * @param frames 2–100 image files (JPEG or PNG recommended)
     * @param jobId  optional job identifier; if supplied the result is cached for
     *               later retrieval via {@code GET /api/analysis/merge-result/{jobId}}
     * @return merged PNG image as binary response
     */
    @PostMapping(value = "/merge-stack", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Merge stack frames into a PNG")
    public ResponseEntity<?> mergeStack(
            @RequestPart("frames") List<MultipartFile> frames,
            @RequestParam(value = "jobId", required = false) String jobId) throws IOException {

        if (frames == null || frames.size() < 2) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "At least 2 frames are required for merging"));
        }
        if (frames.size() > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Maximum 100 frames supported per merge"));
        }

        List<byte[]> frameData = new ArrayList<>(frames.size());
        for (MultipartFile frame : frames) {
            frameData.add(frame.getBytes());
        }

        byte[] merged = stackMerger.merge(frameData);

        if (merged.length == 0) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Stack merge produced no output — check frame format"));
        }

        if (jobId != null && !jobId.isBlank()) {
            mergeResultCache.put(jobId, merged);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(merged);
    }

    /**
     * Returns the cached merge result for the given job, if available.
     *
     * @param jobId the job identifier used when calling {@code /merge-stack}
     * @return the merged PNG image, or 404 if no result is cached for this job
     */
    @GetMapping("/merge-result/{jobId}")
    @Operation(summary = "Get cached merged PNG by job id")
    public ResponseEntity<?> getMergeResult(@PathVariable String jobId) {
        byte[] cached = mergeResultCache.get(jobId);
        if (cached == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(cached);
    }
}
