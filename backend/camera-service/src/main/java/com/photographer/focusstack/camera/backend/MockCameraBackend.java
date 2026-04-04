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

    /**
     * After this many captures, all subsequent calls to {@link #capture()} will
     * block for 30 seconds to simulate a network timeout. 0 = disabled.
     */
    private final int simulateTimeoutAfterFrames;

    /**
     * After this many captures, {@link #capture()} throws a {@link RuntimeException}
     * to simulate a Wi-Fi disconnect / camera connection drop. 0 = disabled.
     */
    private final int simulateDisconnectAfterFrames;

    /**
     * The capture at this 1-based index returns an empty (zero-byte) image to
     * simulate a transmission error. 0 = disabled.
     */
    private final int simulateEmptyFrameAt;

    /**
     * The capture at this 1-based index returns a dramatically different image to
     * simulate unexpected subject movement between frames. 0 = disabled.
     */
    private final int simulateMovementAt;

    private final AtomicInteger focusPosition = new AtomicInteger(0);
    private final AtomicInteger captureCount  = new AtomicInteger(0);

    /** Standard constructor — no failure simulation. */
    public MockCameraBackend(long delayMs) {
        this(delayMs, 0, 0, 0, 0);
    }

    /**
     * Full constructor with all failure-simulation parameters.
     *
     * @param delayMs                      artificial delay per operation (ms)
     * @param simulateTimeoutAfterFrames   block indefinitely after N captures (0 = off)
     * @param simulateDisconnectAfterFrames throw after N captures (0 = off)
     * @param simulateEmptyFrameAt         return empty image at capture N (0 = off)
     * @param simulateMovementAt           return movement-like image at capture N (0 = off)
     */
    public MockCameraBackend(long delayMs,
                             int simulateTimeoutAfterFrames,
                             int simulateDisconnectAfterFrames,
                             int simulateEmptyFrameAt,
                             int simulateMovementAt) {
        this.delayMs                      = delayMs;
        this.simulateTimeoutAfterFrames   = simulateTimeoutAfterFrames;
        this.simulateDisconnectAfterFrames = simulateDisconnectAfterFrames;
        this.simulateEmptyFrameAt         = simulateEmptyFrameAt;
        this.simulateMovementAt           = simulateMovementAt;
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

        int captureIndex = captureCount.incrementAndGet(); // 1-based

        // Timeout simulation: block for 30 s after threshold
        if (simulateTimeoutAfterFrames > 0 && captureIndex > simulateTimeoutAfterFrames) {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Disconnect simulation: throw after threshold
        if (simulateDisconnectAfterFrames > 0 && captureIndex > simulateDisconnectAfterFrames) {
            throw new RuntimeException(
                    "Simulated Wi-Fi disconnect after frame " + simulateDisconnectAfterFrames);
        }

        int pos = focusPosition.getAndIncrement();
        String imageId = UUID.randomUUID().toString();

        // Empty frame simulation
        if (simulateEmptyFrameAt > 0 && captureIndex == simulateEmptyFrameAt) {
            return new CaptureResult(
                    imageId, "IMG_EMPTY_" + pos + ".jpg", "/mock/captures/" + imageId + ".jpg",
                    Instant.now(), pos, 0, new byte[0]);
        }

        // Movement simulation: produce a drastically different (noise-like) image
        byte[] imageData;
        if (simulateMovementAt > 0 && captureIndex == simulateMovementAt) {
            imageData = generateMovementImage();
        } else {
            imageData = generateSyntheticImage(pos);
        }

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

    /**
     * Generates a pseudo-random noise image to simulate sudden subject movement.
     * The high-frequency variation will trigger the motion detector.
     */
    private byte[] generateMovementImage() {
        int w = 64, h = 64;
        byte[] data = new byte[w * h];
        for (int i = 0; i < data.length; i++) {
            // XOR-based noise pattern — maximally different from a checkerboard
            data[i] = (byte) ((i * 0x6B + 0xA5) & 0xFF);
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
