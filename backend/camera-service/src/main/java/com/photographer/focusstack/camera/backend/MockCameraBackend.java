package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class MockCameraBackend implements CameraBackend {

    private final long delayMs;
    private final AtomicInteger focusPosition = new AtomicInteger(0);

    public MockCameraBackend(long delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    public CameraInfo getInfo() {
        simulateDelay();
        return new CameraInfo(
                "mock-camera-001",
                "Mock Camera",
                "Mock Camera",
                "mock",
                "localhost",
                "1.0.0"
        );
    }

    @Override
    public LiveViewFrame getLiveView() {
        simulateDelay();
        byte[] data = new byte[640 * 480];
        int pos = focusPosition.get();
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i + pos) % 256);
        }
        return new LiveViewFrame(data, 640, 480, Instant.now(), "image/jpeg");
    }

    @Override
    public CaptureResult capture() {
        simulateDelay();
        int pos = focusPosition.getAndIncrement();
        String imageId = UUID.randomUUID().toString();
        // Generate synthetic image data that varies with focus position
        // to simulate sharpness changes across the focus sweep.
        byte[] imageData = generateSyntheticImage(pos);
        return new CaptureResult(
                imageId,
                "IMG_" + pos + ".jpg",
                "/mock/captures/" + imageId + ".jpg",
                Instant.now(),
                pos,
                imageData.length,
                imageData
        );
    }

    /**
     * Maximum checkerboard square size (pixels) used in the synthetic image generator.
     * At the sharpness peak the stride is {@code MAX_CHECKERBOARD_STRIDE / sharpnessFactor},
     * so a smaller stride (tighter pattern) represents a sharper image.
     */
    private static final double MAX_CHECKERBOARD_STRIDE = 4.0;

    /** Creates a 64×64 pixel synthetic image whose sharpness peaks at focus position 50. */
    private byte[] generateSyntheticImage(int focusPosition) {
        int w = 64, h = 64;
        byte[] data = new byte[w * h];
        // Sharpness peak at position 50; simulate edge contrast declining away from peak
        double distFromPeak = Math.abs(focusPosition - 50.0);
        double sharpnessFactor = Math.max(0.1, 1.0 - distFromPeak / 100.0);
        int stride = Math.max(1, (int) (MAX_CHECKERBOARD_STRIDE / sharpnessFactor));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Checkerboard pattern; tighter (smaller stride) = sharper
                boolean bright = ((x / stride + y / stride) % 2 == 0);
                data[y * w + x] = (byte) (bright ? 220 : 30);
            }
        }
        return data;
    }

    @Override
    public void setFocus(FocusCommand command) {
        simulateDelay();
        int delta = command.steps();
        if (command.direction() == FocusCommand.Direction.NEAR) {
            focusPosition.addAndGet(-delta);
        } else {
            focusPosition.addAndGet(delta);
        }
    }

    @Override
    public CameraState getState() {
        simulateDelay();
        return new CameraState(
                true,
                85,
                10_000_000L,
                focusPosition.get(),
                "1/125",
                "f/8.0",
                "ISO 100",
                true
        );
    }

    @Override
    public boolean supports(Operation operation) {
        return true;
    }

    private void simulateDelay() {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
