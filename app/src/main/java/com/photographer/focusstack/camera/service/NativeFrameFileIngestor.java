package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Streams bounded native camera media to disk without materializing a full frame in heap. */
final class NativeFrameFileIngestor {

    private static final int DOWNLOAD_CHUNK_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CHUNKS = 128;
    private static final long MAX_FRAME_BYTES = 1024L * 1024L * 1024L;

    private NativeFrameFileIngestor() {
    }

    static String ingest(
            FrameDataStore frameDataStore,
            CameraMediaProvider mediaProvider,
            CameraMediaReference reference) {
        Objects.requireNonNull(frameDataStore, "frameDataStore");
        Objects.requireNonNull(mediaProvider, "mediaProvider");
        Objects.requireNonNull(reference, "reference");

        Path temporary = null;
        boolean committed = false;
        try {
            temporary = Files.createTempFile("focusstack-native-", ".part");
            long offset = 0L;
            int chunks = 0;
            try (OutputStream output = Files.newOutputStream(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                while (true) {
                    if (++chunks > MAX_CHUNKS) {
                        throw new CameraException(
                                "Native media exceeded the bounded chunk count");
                    }
                    CameraMediaProvider.MediaChunk chunk = mediaProvider.downloadMedia(
                            reference, offset, DOWNLOAD_CHUNK_BYTES);
                    if (chunk.offset() != offset) {
                        throw new CameraException(
                                "Native media returned a non-contiguous chunk");
                    }
                    byte[] data = chunk.data();
                    if (data.length == 0 && !chunk.complete()) {
                        throw new CameraException(
                                "Native media transfer made no progress");
                    }
                    long nextOffset;
                    try {
                        nextOffset = Math.addExact(offset, data.length);
                    } catch (ArithmeticException exception) {
                        throw new CameraException(
                                "Native media size exceeds the supported range", exception);
                    }
                    if (nextOffset > MAX_FRAME_BYTES
                            || chunk.totalSize() != null
                            && chunk.totalSize() > MAX_FRAME_BYTES) {
                        throw new CameraException(
                                "Native media exceeds the 1 GiB safety limit");
                    }
                    output.write(data);
                    offset = nextOffset;
                    if (chunk.complete()) {
                        if (chunk.totalSize() != null
                                && chunk.totalSize() != offset) {
                            throw new CameraException(
                                    "Native media completed with an inconsistent total size");
                        }
                        break;
                    }
                }
            }

            String frameId = UUID.randomUUID().toString();
            frameDataStore.putFile(frameId, reference.fileName(), temporary);
            committed = true;
            return frameId;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to stream native camera media", exception);
        } finally {
            if (!committed && temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The primary failure remains authoritative.
                }
            }
        }
    }

    static boolean isPreviewable(String fileName) {
        if (fileName == null) {
            return false;
        }
        String normalized = fileName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".jpg")
                || normalized.endsWith(".jpeg")
                || normalized.endsWith(".png");
    }
}
