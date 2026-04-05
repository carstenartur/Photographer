package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Canon CCAPI backend implementation for Canon PowerShot cameras (e.g. G5 X Mark II,
 * G7 X Mark III) that support the Canon Camera Control API (CCAPI) over Wi-Fi.
 *
 * <h3>CCAPI endpoints used</h3>
 * <ul>
 *   <li>{@code GET  /ccapi/ver100/deviceinformation} — camera model, serial, firmware</li>
 *   <li>{@code GET  /ccapi/ver100/shooting/liveview/flip} — MJPEG live-view frame</li>
 *   <li>{@code POST /ccapi/ver100/shooting/control/shutterbutton} — trigger capture</li>
 *   <li>{@code POST /ccapi/ver100/shooting/control/mfdrive} — manual focus drive</li>
 *   <li>{@code GET  /ccapi/ver100/devicestatus/battery} — battery level</li>
 *   <li>{@code GET  /ccapi/ver100/devicestatus/storage} — storage free space</li>
 * </ul>
 *
 * <p>CCAPI is available over the camera's built-in Wi-Fi access-point or via
 * infrastructure mode.  The base URL is typically {@code http://192.168.1.2:8080}
 * when the camera acts as an AP, or the DHCP-assigned IP when in client mode.
 */
public class CanonCcapiBackend implements CameraBackend {

    private static final Logger log = LoggerFactory.getLogger(CanonCcapiBackend.class);

    /** Default camera model reported when the CCAPI device-info call is unavailable. */
    static final String DEFAULT_MODEL = "Canon PowerShot G5 X Mark II";

    private static final String PATH_DEVICE_INFO    = "/ccapi/ver100/deviceinformation";
    private static final String PATH_LIVEVIEW       = "/ccapi/ver100/shooting/liveview/flip";
    private static final String PATH_SHUTTER        = "/ccapi/ver100/shooting/control/shutterbutton";
    private static final String PATH_MF_DRIVE       = "/ccapi/ver100/shooting/control/mfdrive";
    private static final String PATH_BATTERY        = "/ccapi/ver100/devicestatus/battery";
    private static final String PATH_STORAGE        = "/ccapi/ver100/devicestatus/storage";

    private final String baseUrl;
    private final RestTemplate restTemplate;

    /**
     * Tracks the last commanded focus position so it can be reported in
     * {@link #capture()} and {@link #getState()} (CCAPI does not expose the
     * current focus step value directly).
     */
    private final AtomicInteger focusPosition = new AtomicInteger(0);

    public CanonCcapiBackend(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
    }

    @Override
    public CameraInfo getInfo() {
        try {
            DeviceInformation info = restTemplate.getForObject(
                    baseUrl + PATH_DEVICE_INFO, DeviceInformation.class);
            String model = (info != null && info.productname() != null)
                    ? info.productname() : DEFAULT_MODEL;
            String serial = (info != null && info.serialnumber() != null)
                    ? info.serialnumber() : "unknown";
            String firmware = (info != null && info.firmwareversion() != null)
                    ? info.firmwareversion() : "unknown";
            return new CameraInfo("ccapi-" + serial, "Canon Camera", model, "ccapi", baseUrl, firmware);
        } catch (RestClientException e) {
            log.warn("CCAPI deviceinformation unavailable ({}), using defaults", e.getMessage());
            return new CameraInfo("ccapi-camera", "Canon Camera", DEFAULT_MODEL, "ccapi", baseUrl, "unknown");
        }
    }

    @Override
    public LiveViewFrame getLiveView() {
        try {
            byte[] data = restTemplate.getForObject(baseUrl + PATH_LIVEVIEW, byte[].class);
            if (data == null) {
                data = new byte[0];
            }
            return new LiveViewFrame(data, 960, 640, Instant.now(), MediaType.IMAGE_JPEG_VALUE);
        } catch (RestClientException e) {
            throw new CameraException("Failed to get live view: " + e.getMessage(), e);
        }
    }

    @Override
    public CaptureResult capture() {
        try {
            // Trigger the shutter without auto-focus (af=false) so the pre-set
            // manual focus position is preserved between stack frames.
            restTemplate.postForObject(
                    baseUrl + PATH_SHUTTER,
                    Map.of("af", false),
                    String.class);
            String imageId = UUID.randomUUID().toString();
            return new CaptureResult(
                    imageId,
                    "capture-" + imageId + ".jpg",
                    PATH_SHUTTER,
                    Instant.now(),
                    focusPosition.get(),
                    0L,
                    null // images are stored on the camera card and downloaded separately
            );
        } catch (RestClientException e) {
            throw new CameraException("Failed to capture image: " + e.getMessage(), e);
        }
    }

    @Override
    public void setFocus(FocusCommand command) {
        try {
            // CCAPI manual focus drive: map step size (<=1 -> "1", <=5 -> "2", else -> "3") to CCAPI kind suffix
            String stepKind = toMfDriveKind(command.steps());
            String direction = command.direction() == FocusCommand.Direction.NEAR ? "near" : "far";
            String kind = direction + stepKind; // e.g. "near1", "far2"
            restTemplate.postForObject(
                    baseUrl + PATH_MF_DRIVE,
                    Map.of("kind", kind),
                    String.class);
            // Update tracked position: NEAR decrements, FAR increments
            int delta = command.direction() == FocusCommand.Direction.NEAR
                    ? -command.steps() : command.steps();
            focusPosition.addAndGet(delta);
        } catch (RestClientException e) {
            throw new CameraException("Failed to set focus: " + e.getMessage(), e);
        }
    }

    @Override
    public CameraState getState() {
        int batteryLevel = 100;
        long storageAvailable = 0L;
        boolean connected = false;
        try {
            BatteryStatus battery = restTemplate.getForObject(
                    baseUrl + PATH_BATTERY, BatteryStatus.class);
            if (battery != null && battery.level() != null) {
                batteryLevel = parseBatteryLevel(battery.level());
            }
            connected = true;
        } catch (RestClientException e) {
            log.debug("CCAPI battery status unavailable: {}", e.getMessage());
        }
        try {
            StorageStatus storage = restTemplate.getForObject(
                    baseUrl + PATH_STORAGE, StorageStatus.class);
            if (storage != null && storage.spaceBytes() != null) {
                storageAvailable = storage.spaceBytes();
            }
            connected = true;
        } catch (RestClientException e) {
            log.debug("CCAPI storage status unavailable: {}", e.getMessage());
        }
        return new CameraState(
                connected, batteryLevel, storageAvailable, focusPosition.get(),
                "auto", "auto", "auto", false
        );
    }

    @Override
    public boolean supports(Operation operation) {
        return switch (operation) {
            case LIVE_VIEW, CAPTURE, FOCUS_CONTROL, SETTINGS_READ -> true;
            case ZOOM_CONTROL, SETTINGS_WRITE -> false;
        };
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Maps an abstract step count to a CCAPI MF-drive kind suffix.
     * CCAPI supports three granularities: 1 (fine), 2 (medium), 3 (coarse).
     */
    private static String toMfDriveKind(int steps) {
        if (steps <= 1) return "1";
        if (steps <= 5) return "2";
        return "3";
    }

    /**
     * Parses a CCAPI battery level string ("high", "middle", "low", "empty")
     * or a numeric percentage string ("80") into an integer 0–100.
     * Returns 100 for null or unrecognised input. Trims whitespace before parsing.
     */
    static int parseBatteryLevel(String level) {
        if (level == null) {
            return 100;
        }
        String normalized = level.trim().toLowerCase();
        return switch (normalized) {
            case "high"   -> 80;
            case "middle" -> 50;
            case "low"    -> 20;
            case "empty"  -> 0;
            default -> {
                try { yield Math.max(0, Math.min(100, Integer.parseInt(normalized))); }
                catch (NumberFormatException e) { yield 100; }
            }
        };
    }

    // ── CCAPI response DTOs ───────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeviceInformation(
            @JsonProperty("productname")    String productname,
            @JsonProperty("serialnumber")   String serialnumber,
            @JsonProperty("firmwareversion") String firmwareversion
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatteryStatus(
            @JsonProperty("level") String level
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StorageStatus(
            @JsonProperty("spaceavailablebytes") Long spaceBytes
    ) {}
}
