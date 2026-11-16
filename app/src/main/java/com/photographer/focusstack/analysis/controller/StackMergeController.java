package com.photographer.focusstack.analysis.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.photographer.focusstack.app.api.ApiPaths;
import com.photographer.focusstack.analysis.service.StackMerger;
import com.photographer.focusstack.analysis.service.StackFileProcessor;
import com.photographer.focusstack.camera.service.FrameDataStore;
import com.photographer.focusstack.camera.service.FrameDataStore.OriginalFrame;
import com.photographer.focusstack.camera.service.StackingJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
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
 * can later be retrieved via {@code GET /api/v1/analysis/merge-result/{jobId}}. The legacy
     * {@code /api/**} route remains a temporary compatibility alias.
 */
@RestController
@RequestMapping({ApiPaths.V1 + "/analysis", ApiPaths.LEGACY + "/analysis"})
@Tag(name = "Stack Merge", description = "Focus-stack merge and merge-result retrieval endpoints.")
public class StackMergeController {

    private final StackMerger stackMerger;
    private final StackFileProcessor stackFiles;
    private final StackingJobService jobs;
    private final FrameDataStore frames;

    private static final long MAX_CACHE_BYTES = 64L << 20;
    private static final int MIN_CACHE_ENTRY_WEIGHT = 128 << 10;
    private final Cache<CacheKey, CachedMerge> mergeResultCache = Caffeine.newBuilder()
            .maximumWeight(Math.min(MAX_CACHE_BYTES, Runtime.getRuntime().maxMemory() / 16))
            .weigher((CacheKey key, CachedMerge value) -> Math.max(MIN_CACHE_ENTRY_WEIGHT, value.png().length))
            .build();

    public StackMergeController(
            StackMerger stackMerger,
            StackFileProcessor stackFiles,
            StackingJobService jobs,
            FrameDataStore frames) {
        this.stackMerger = stackMerger;
        this.stackFiles = stackFiles;
        this.jobs = jobs;
        this.frames = frames;
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
            mergeResultCache.put(new CacheKey(jobId, false), new CachedMerge(List.of(), merged, null, 0));
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(merged);
    }

    /** Develops and merges the original files already stored for a capture job. */
    @PostMapping("/merge-stored-stack/{jobId}")
    @Operation(summary = "Merge the original stored frames for a capture job")
    public ResponseEntity<?> mergeStoredStack(@PathVariable String jobId) throws IOException {
        List<String> ids = jobs.getJob(jobId).frames();
        if (ids.size() < 2 || ids.size() > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Between 2 and 100 frames are required for merging"));
        }
        var cacheKey = new CacheKey(jobId, true);
        CachedMerge cached = mergeResultCache.getIfPresent(cacheKey);
        if (cached != null && ids.equals(cached.frameIds())) {
            return pngResponse(cached);
        }
        List<OriginalFrame> originals = new ArrayList<>(ids.size());
        for (String id : ids) {
            OriginalFrame original = frames.getOriginalFrame(id);
            if (original != null) {
                originals.add(original);
            }
        }
        if (originals.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No stored stack originals were found"));
        }
        StackFileProcessor.Result result = stackFiles.merge(originals);
        var merged = new CachedMerge(ids, result.png(), result.source(), result.frameCount());
        mergeResultCache.put(cacheKey, merged);
        return pngResponse(merged);
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
        CachedMerge cached = mergeResultCache.getIfPresent(new CacheKey(jobId, true));
        if (cached == null) {
            cached = mergeResultCache.getIfPresent(new CacheKey(jobId, false));
        }
        if (cached == null) {
            return ResponseEntity.notFound().build();
        }
        return pngResponse(cached);
    }

    private static ResponseEntity<byte[]> pngResponse(CachedMerge merged) {
        var response = ResponseEntity.ok().contentType(MediaType.IMAGE_PNG);
        if (merged.source() != null) {
            response.header("X-Photographer-Stack-Source", merged.source().name())
                    .header("X-Photographer-Stack-Frame-Count", Integer.toString(merged.frameCount()));
        }
        return response.body(merged.png());
    }

    private record CacheKey(String jobId, boolean storedOriginals) {}

    private record CachedMerge(List<String> frameIds, byte[] png, StackFileProcessor.Source source, int frameCount) {}
}
