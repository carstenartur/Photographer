package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.camera.exception.CameraException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class CanonCcapiBackend implements CameraBackend {

    private final String baseUrl;
    private final RestTemplate restTemplate;

    public CanonCcapiBackend(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
    }

    @Override
    public CameraInfo getInfo() {
        try {
            // TODO: Implement full CCAPI device info retrieval
            // GET {baseUrl}/ccapi/ver100/deviceinformation
            return new CameraInfo(
                    "ccapi-camera",
                    "Canon Camera",
                    "Canon EOS R5",
                    "ccapi",
                    baseUrl,
                    "1.0.0"
            );
        } catch (RestClientException e) {
            throw new CameraException("Failed to get camera info: " + e.getMessage(), e);
        }
    }

    @Override
    public LiveViewFrame getLiveView() {
        try {
            // TODO: Implement CCAPI live view stream
            // GET {baseUrl}/ccapi/ver100/shooting/liveview/flip
            byte[] data = restTemplate.getForObject(
                    baseUrl + "/ccapi/ver100/shooting/liveview/flip", byte[].class);
            if (data == null) {
                data = new byte[0];
            }
            return new LiveViewFrame(data, 960, 640, Instant.now(), "image/jpeg");
        } catch (RestClientException e) {
            throw new CameraException("Failed to get live view: " + e.getMessage(), e);
        }
    }

    @Override
    public CaptureResult capture() {
        try {
            // TODO: Implement full CCAPI capture with proper result parsing
            // POST {baseUrl}/ccapi/ver100/shooting/control/shutterbutton
            restTemplate.postForObject(
                    baseUrl + "/ccapi/ver100/shooting/control/shutterbutton",
                    null, String.class);
            return new CaptureResult(
                    UUID.randomUUID().toString(),
                    "capture.jpg",
                    "/ccapi/capture",
                    Instant.now(),
                    0,
                    0L,
                    null // CCAPI stores images on camera card; download separately
            );
        } catch (RestClientException e) {
            throw new CameraException("Failed to capture image: " + e.getMessage(), e);
        }
    }

    @Override
    public void setFocus(FocusCommand command) {
        try {
            // TODO: Implement CCAPI focus control
            // POST {baseUrl}/ccapi/ver100/shooting/control/af
            String direction = command.direction() == FocusCommand.Direction.NEAR ? "near" : "far";
            restTemplate.postForObject(
                    baseUrl + "/ccapi/ver100/shooting/control/af",
                    Map.of("direction", direction, "steps", command.steps()),
                    String.class);
        } catch (RestClientException e) {
            throw new CameraException("Failed to set focus: " + e.getMessage(), e);
        }
    }

    @Override
    public CameraState getState() {
        try {
            // TODO: Implement full CCAPI state retrieval
            // GET {baseUrl}/ccapi/ver100/devicestatus/battery
            // GET {baseUrl}/ccapi/ver100/devicestatus/storage
            return new CameraState(
                    true, 100, 0L, 0,
                    "1/125", "f/8.0", "ISO 100", false
            );
        } catch (RestClientException e) {
            throw new CameraException("Failed to get camera state: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(Operation operation) {
        // TODO: Query CCAPI capabilities
        return switch (operation) {
            case LIVE_VIEW, CAPTURE, FOCUS_CONTROL, SETTINGS_READ -> true;
            case ZOOM_CONTROL, SETTINGS_WRITE -> false;
        };
    }
}
