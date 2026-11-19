package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.camera.service.RawImagePreviewService;
import com.photographer.focusstack.common.camera.CaptureResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BirdCaptureStoreTest {
    @TempDir Path directory;

    @Test
    void preservesRawBytesAndLabelsLiveViewFallbackTruthfully() throws Exception {
        var previews = mock(RawImagePreviewService.class);
        var store = new BirdCaptureStore(directory.toString(), previews);
        byte[] original = {1, 2, 3, 4};
        byte[] liveView = BirdFrameAnalyzerTest.jpeg(10);
        var saved = store.save(UUID.randomUUID().toString(), new CaptureResult("photo", "../../IMG.CR3", null,
                Instant.now(), 0, original.length, original), liveView);
        assertArrayEquals(original, Files.readAllBytes(Path.of(saved.original().filePath())));
        assertArrayEquals(liveView, Files.readAllBytes(Path.of(saved.previewPath())));
        assertEquals("live-view-at-trigger", saved.previewSource());
        assertTrue(Path.of(saved.original().filePath()).getParent().equals(directory));
        verify(previews).extractPreview(any(), any());
    }

    @Test
    void missingPhotoBytesDoesNotCreateAFakeImageOrEventFile() throws Exception {
        var store = new BirdCaptureStore(directory.toString(), new RawImagePreviewService());
        assertThrows(java.io.IOException.class, () -> store.save(UUID.randomUUID().toString(),
                CaptureResult.of("image", "IMG.CR3", "camera/path", Instant.now(), 0, 100), BirdFrameAnalyzerTest.jpeg(1)));
        try (var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
    }
}
