package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Camera backend that replays images from a folder on disk.
 *
 * <p>Useful for testing the Wildlife Observer pipeline with real images
 * without a connected camera. Images are served in alphabetical order,
 * cycling back to the start when all images have been served.</p>
 */
public class FolderCameraBackend implements CameraBackend {

    private static final Logger log = LoggerFactory.getLogger(FolderCameraBackend.class);

    private final Path imageFolder;
    private final List<Path> imageFiles;
    private final AtomicInteger index = new AtomicInteger(0);
    private final CameraInfo cameraInfo;

    public FolderCameraBackend(Path imageFolder) {
        this.imageFolder = imageFolder;
        this.cameraInfo = new CameraInfo(
                "folder-" + imageFolder.getFileName(),
                "Folder Replay",
                "FolderCameraBackend",
                "folder",
                imageFolder.toString(),
                "1.0"
        );
        this.imageFiles = loadImageFiles(imageFolder);
        log.info("FolderCameraBackend: loaded {} images from {}", imageFiles.size(), imageFolder);
    }

    @Override
    public CameraInfo getInfo() {
        return cameraInfo;
    }

    @Override
    public LiveViewFrame getLiveView() {
        if (imageFiles.isEmpty()) {
            return new LiveViewFrame(new byte[0], 0, 0, Instant.now(), "image/jpeg");
        }
        int i = index.get() % imageFiles.size();
        byte[] data = readFile(imageFiles.get(i));
        return new LiveViewFrame(data, 0, 0, Instant.now(), "image/jpeg");
    }

    @Override
    public CaptureResult capture() {
        if (imageFiles.isEmpty()) {
            throw new CameraException("No images available in folder: " + imageFolder);
        }
        int i = index.getAndIncrement() % imageFiles.size();
        Path file = imageFiles.get(i);
        byte[] data = readFile(file);
        return new CaptureResult(
                UUID.randomUUID().toString(),
                file.getFileName().toString(),
                file.toAbsolutePath().toString(),
                Instant.now(),
                i,
                data.length,
                data
        );
    }

    @Override
    public void setFocus(FocusCommand command) {
        // No-op for folder replay
        log.debug("FolderCameraBackend: setFocus({}) — no-op", command);
    }

    @Override
    public CameraState getState() {
        return new CameraState(true, 100, 100L * 1024 * 1024 * 1024,
                index.get(), "1/250", "f/5.6", "400", false);
    }

    @Override
    public boolean supports(Operation operation) {
        return switch (operation) {
            case CAPTURE, LIVE_VIEW -> true;
            default -> false;
        };
    }

    private List<Path> loadImageFiles(Path folder) {
        try {
            if (!Files.isDirectory(folder)) {
                return List.of();
            }
            try (var files = Files.list(folder)) {
                return files
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".jpg") || name.endsWith(".jpeg")
                                    || name.endsWith(".png") || name.endsWith(".raw");
                        })
                        .sorted()
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            log.warn("Failed to list images in {}: {}", folder, e.getMessage());
            return List.of();
        }
    }

    private byte[] readFile(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read image file: " + path, e);
        }
    }
}
