package com.photographer.focusstack.analysis.controller;

import com.photographer.focusstack.analysis.service.StackMerger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
 */
@RestController
@RequestMapping("/api/analysis")
public class StackMergeController {

    private final StackMerger stackMerger;

    public StackMergeController(StackMerger stackMerger) {
        this.stackMerger = stackMerger;
    }

    /**
     * Merges the supplied frames into a single all-in-focus image using EDF.
     *
     * @param frames 2–100 image files (JPEG or PNG recommended)
     * @return merged PNG image as binary response
     */
    @PostMapping(value = "/merge-stack", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> mergeStack(
            @RequestPart("frames") List<MultipartFile> frames) throws IOException {

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

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(merged);
    }
}
