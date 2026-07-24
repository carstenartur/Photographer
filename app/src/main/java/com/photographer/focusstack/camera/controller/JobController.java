package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.app.api.ApiPaths;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.search.SearchService;
import com.photographer.focusstack.camera.service.FrameDataStore;
import com.photographer.focusstack.camera.service.RawImagePreviewService;
import com.photographer.focusstack.camera.service.StackingJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping({ApiPaths.V1 + "/jobs", ApiPaths.LEGACY + "/jobs"})
@Tag(name = "Jobs", description = "Focus stacking job lifecycle and frame export endpoints.")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final StackingJobService jobService;
    private final SearchService searchService;
    private final FrameDataStore frameDataStore;
    private final RawImagePreviewService rawPreviewService;

    public JobController(StackingJobService jobService,
                         SearchService searchService,
                         FrameDataStore frameDataStore,
                         RawImagePreviewService rawPreviewService) {
        this.jobService = jobService;
        this.searchService = searchService;
        this.frameDataStore = frameDataStore;
        this.rawPreviewService = rawPreviewService;
    }

    @GetMapping
    @Operation(summary = "List stacking jobs")
    public ResponseEntity<List<StackingJob>> listJobs() {
        return ResponseEntity.ok(jobService.getAllJobs());
    }

    @PostMapping
    @Operation(summary = "Create stacking job")
    public ResponseEntity<StackingJob> createJob(@Valid @RequestBody CreateJobRequest request) {
        StackingJob job = jobService.createJob(request.cameraProfileId(), request.parameters());
        return ResponseEntity.status(HttpStatus.CREATED).body(job);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get stacking job by id")
    public ResponseEntity<StackingJob> getJob(@PathVariable String id) {
        return ResponseEntity.ok(jobService.getJob(id));
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Start stacking job")
    public ResponseEntity<StackingJob> startJob(@PathVariable String id) {
        StackingJob running = jobService.startJob(id);
        return ResponseEntity.accepted().body(running);
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause stacking job")
    public ResponseEntity<Void> pauseJob(@PathVariable String id) {
        jobService.pauseJob(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel stacking job")
    public ResponseEntity<Void> cancelJob(@PathVariable String id) {
        jobService.cancelJob(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete stacking job")
    public ResponseEntity<Void> deleteJob(@PathVariable String id) {
        if (jobService.deleteJob(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Serves a browser-displayable image for a single captured frame.
     *
     * <p>If the captured file is a Canon RAW (CR3/CR2), the embedded JPEG preview
     * (~1620×1080) is extracted and served so the browser can display it without
     * a RAW decoder. JPEG and PNG files are served directly.
     *
     * @param id      the job id
     * @param frameId the frame image id
     * @return the image bytes with correct content type, or 404 if not found
     */
    @GetMapping("/{id}/frames/{frameId}/image")
    @Operation(summary = "Get browser-displayable image for a captured frame")
    public ResponseEntity<byte[]> getFrameImage(
            @PathVariable String id,
            @PathVariable String frameId) {
        // Validate that the frame belongs to this job
        StackingJob job = jobService.getJob(id);
        if (!job.frames().contains(frameId)) {
            return ResponseEntity.notFound().build();
        }
        FrameDataStore.FrameEntry entry = frameDataStore.getEntry(frameId);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }

        String fileName = entry.fileName();
        byte[] imageBytes = entry.data();
        MediaType contentType;

        // Canon RAW files (CR3/CR2) cannot be displayed by browsers.
        // Extract the embedded JPEG preview for display.
        if (rawPreviewService.isRawFile(fileName)) {
            RawImagePreviewService.PreviewResult preview =
                    rawPreviewService.extractPreview(imageBytes, fileName);
            if (preview != null && preview.jpegBytes() != null) {
                imageBytes = preview.jpegBytes();
                fileName = preview.fileName();
                contentType = MediaType.IMAGE_JPEG;
            } else {
                // Fallback: serve the raw bytes as octet-stream (browser won't display it,
                // but the user can still download it)
                log.warn("Frame {} ({}): CR3 preview extraction failed — serving raw bytes",
                        frameId, entry.fileName());
                contentType = MediaType.APPLICATION_OCTET_STREAM;
            }
        } else {
            // Derive content type from filename extension
            contentType = deriveContentType(fileName);
        }

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + sanitizeFileName(fileName) + "\"")
                .body(imageBytes);
    }

    /** Sanitizes a filename for safe use in a Content-Disposition header value.
     * Keeps only alphanumerics, dots, hyphens, and underscores; replaces all
     * other characters (including CR, LF, quotes, semicolons, and equals signs
     * that have meaning in HTTP header syntax) with underscores.
     * Rejects path traversal sequences. */
    static String sanitizeFileName(String fileName) {
        if (fileName == null) return "download";
        String sanitized = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        // Prevent path traversal via ".."
        sanitized = sanitized.replace("..", "_");
        return sanitized;
    }

    /** Derives a browser-friendly {@link MediaType} from a filename extension. */
    private static MediaType deriveContentType(String fileName) {
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".png")) {
                return MediaType.IMAGE_PNG;
            } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return MediaType.IMAGE_JPEG;
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * Exports all captured frames for a job as a streamed ZIP archive.
     *
     * <p>Only frames whose raw data was cached in {@link FrameDataStore} during
     * capture are included.  Each entry uses the original captured filename from
     * the camera backend.
     *
     * @param id the job id
     * @return a streamed ZIP file, or 404 if the job is not found
     */
    @GetMapping("/{id}/export")
    @Operation(summary = "Export captured frames as ZIP")
    public ResponseEntity<StreamingResponseBody> exportFrames(@PathVariable String id) {
        StackingJob job = jobService.getJob(id);
        List<String> frameIds = job.frames();

        StreamingResponseBody stream = outputStream -> {
            try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
                int index = 1;
                for (String frameId : frameIds) {
                    FrameDataStore.FrameEntry entry = frameDataStore.getEntry(frameId);
                    String entryName = String.format("frame_%03d_%s",
                            index++,
                            entry != null ? entry.fileName() : frameId + ".bin");
                    zip.putNextEntry(new ZipEntry(entryName));
                    if (entry != null) {
                        zip.write(entry.data());
                    }
                    zip.closeEntry();
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"job-" + id + "-frames.zip\"")
                .body(stream);
    }

    @GetMapping("/search")
    @Operation(summary = "Search stacking jobs")
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
    @Operation(summary = "Reindex job and camera search data")
    public ResponseEntity<Void> reindex() {
        searchService.reindexAll();
        return ResponseEntity.ok().build();
    }

    public record CreateJobRequest(
            @NotBlank String cameraProfileId,
            @Valid StackingParameters parameters
    ) {}
}
