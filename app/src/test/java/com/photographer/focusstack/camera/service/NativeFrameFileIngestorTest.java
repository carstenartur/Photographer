package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeFrameFileIngestorTest {

    @TempDir
    Path tempDir;

    @Test
    void streamsSeveralChunksToDiskWithoutPopulatingTheHotCache() {
        FrameDataStore store = new FrameDataStore(tempDir.toString(), 1_024);
        CameraMediaReference reference = new CameraMediaReference(
                "card1",
                "/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_0001.CR3",
                "IMG_0001.CR3", "CR3", "ver140");
        AtomicInteger call = new AtomicInteger();
        CameraMediaProvider provider = new CameraMediaProvider() {
            @Override
            public MediaPage listMedia(MediaQuery query) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MediaChunk downloadMedia(
                    CameraMediaReference ignored,
                    long offset,
                    int maxBytes) {
                int index = call.getAndIncrement();
                byte[] data = index == 0
                        ? "abc".getBytes(StandardCharsets.UTF_8)
                        : "def".getBytes(StandardCharsets.UTF_8);
                return new MediaChunk(reference, offset, data, 6L, index == 1);
            }

            @Override
            public Optional<CameraMediaReference> awaitAddedMedia(
                    EventMode mode,
                    Duration timeout) {
                throw new UnsupportedOperationException();
            }
        };

        String frameId = NativeFrameFileIngestor.ingest(store, provider, reference);

        assertTrue(store.contains(frameId));
        assertFalse(store.isCached(frameId));
        assertArrayEquals("abcdef".getBytes(StandardCharsets.UTF_8), store.get(frameId));
    }

    @Test
    void onlyBrowserDisplayableStillImagesArePreviewable() {
        assertTrue(NativeFrameFileIngestor.isPreviewable("IMG_0001.JPG"));
        assertTrue(NativeFrameFileIngestor.isPreviewable("render.png"));
        assertFalse(NativeFrameFileIngestor.isPreviewable("IMG_0001.CR3"));
        assertFalse(NativeFrameFileIngestor.isPreviewable("clip.mp4"));
    }
}
