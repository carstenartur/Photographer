package com.photographer.focusstack.camera.backend;

import com.canon.focusstack.common.camera.CameraBackend;
import com.canon.focusstack.common.camera.CameraInfo;
import com.canon.focusstack.common.camera.CameraState;
import com.canon.focusstack.common.camera.CaptureResult;
import com.canon.focusstack.common.camera.FocusCommand;
import com.canon.focusstack.common.camera.LiveViewFrame;
import com.canon.focusstack.common.camera.Operation;

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
        return new CaptureResult(
                imageId,
                "IMG_" + pos + ".jpg",
                "/mock/captures/" + imageId + ".jpg",
                Instant.now(),
                pos,
                1024L * 1024
        );
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
