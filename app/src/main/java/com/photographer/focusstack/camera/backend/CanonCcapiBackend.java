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

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;

import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Canon CCAPI backend that <strong>discovers available endpoints dynamically</strong>
 * by querying the camera's {@code /ccapi} root.
 *
 * <p>On first use the backend fetches {@code GET /ccapi} which returns a JSON
 * object listing available API versions and their root URLs.  It then queries
 * the version root to learn the available endpoint categories.  All subsequent
 * calls use the discovered paths — nothing is invented.
 *
 * <h3>Typical CCAPI structure (Canon PowerShot cameras)</h3>
 *
 * <p><strong>Format A</strong> (older firmware / simplified): version value is
 * a root URL string, and a second {@code GET} returns the endpoint list.</p>
 * <pre>
 * GET /ccapi
 * → { "ver100": "/ccapi/ver100", "ver110": "/ccapi/ver110", ... }
 *
 * GET /ccapi/ver100
 * → [ "/ccapi/ver100/deviceinformation", ... ]
 * </pre>
 *
 * <p><strong>Format B</strong> (real hardware, e.g. PowerShot G5 X Mark II):
 * version value is already the full list of endpoint descriptors — no second
 * {@code GET} is needed.</p>
 * <pre>
 * GET /ccapi
 * → { "ver100": [
 *       { "url": "/ccapi/ver100/deviceinformation", "get": true, "post": false, ... },
 *       { "url": "/ccapi/ver100/devicestatus/battery", "get": true, ... },
 *       ...
 *     ] }
 * </pre>
 */
public class CanonCcapiBackend implements CameraBackend {

    private static final Logger log = LoggerFactory.getLogger(CanonCcapiBackend.class);

    /** Default camera model reported when the CCAPI device-info call is unavailable. */
    static final String DEFAULT_MODEL = "Canon Camera";

    private final String baseUrl;
    private final RestTemplate restTemplate;

    /**
     * Tracks the last commanded focus position so it can be reported in
     * {@link #capture()} and {@link #getState()} (CCAPI does not expose the
     * current focus step value directly).
     */
    private final AtomicInteger focusPosition = new AtomicInteger(0);

    /** Discovered endpoint paths (full URL), keyed by a well-known suffix. */
    private final Map<String, String> endpoints = new ConcurrentHashMap<>();
    /** Allowed HTTP methods per endpoint path (populated by Format B discovery). */
    private final Map<String, Set<HttpMethod>> endpointMethods = new ConcurrentHashMap<>();
    private final AtomicBoolean discovered = new AtomicBoolean(false);

    public CanonCcapiBackend(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
    }

    // ── endpoint discovery ──────────────────────────────────────────────────

    /**
     * Queries {@code GET /ccapi} to discover available API versions and their
     * endpoints.  Handles two response formats:
     * <ul>
     *   <li><strong>Format A</strong> – version value is a root URL string
     *       (or single-element list); a second {@code GET} fetches the
     *       endpoint list.</li>
     *   <li><strong>Format B</strong> – version value is already the list of
     *       endpoint descriptor objects ({@code [{url:"...", get:true, ...}]});
     *       no second call is needed.</li>
     * </ul>
     * Results are cached for the lifetime of this backend instance.
     */
    void discoverEndpoints() {
        if (discovered.get()) return;
        try {
            log.info("Discovering CCAPI endpoints at {}/ccapi", baseUrl);

            @SuppressWarnings("unchecked")
            Map<String, Object> versions = restTemplate.getForObject(
                    baseUrl + "/ccapi", Map.class);
            if (versions == null || versions.isEmpty()) {
                log.warn("CCAPI root returned no versions");
                return;
            }
            log.info("CCAPI versions available: {}", versions.keySet());

            // Pick the highest version (e.g. ver110 > ver100)
            String bestVersion = versions.keySet().stream()
                    .filter(k -> k.startsWith("ver"))
                    .sorted().reduce((a, b) -> b).orElse(null);
            if (bestVersion == null) {
                log.warn("No versioned CCAPI entry found in {}", versions.keySet());
                return;
            }

            Object versionValue = versions.get(bestVersion);

            // ── Format B: list of endpoint descriptor objects ────────────
            List<EndpointDescriptor> descriptors = extractDescriptors(versionValue);
            if (descriptors != null) {
                log.info("Using CCAPI {} (descriptor-object format, {} endpoints)",
                        bestVersion, descriptors.size());
                registerDescriptors(descriptors);
                return;
            }

            // ── Format A: version root URL (string or single-element list) ─
            String versionRoot = extractString(versionValue);
            if (versionRoot == null) {
                log.warn("CCAPI version '{}' has no usable URL value: {}",
                        bestVersion, versionValue);
                return;
            }
            String versionUrl = versionRoot.startsWith("http")
                    ? versionRoot : baseUrl + versionRoot;
            log.info("Using CCAPI {}: {}", bestVersion, versionUrl);

            @SuppressWarnings("unchecked")
            List<String> eps = restTemplate.getForObject(versionUrl, List.class);
            if (eps == null || eps.isEmpty()) {
                log.warn("CCAPI version root returned no endpoints");
                return;
            }
            log.info("CCAPI discovered {} endpoints via version root", eps.size());
            registerEndpoints(eps);
        } catch (RestClientException e) {
            log.warn("CCAPI endpoint discovery failed ({}): {}", baseUrl, e.getMessage());
        }
    }

    /**
     * Registers discovered endpoint paths, building full URLs if necessary.
     * Used for Format A discovery (plain string paths, no method info).
     */
    private void registerEndpoints(List<String> endpointPaths) {
        for (String ep : endpointPaths) {
            String fullUrl = ep.startsWith("http") ? ep : baseUrl + ep;
            endpoints.put(ep, fullUrl);
            log.debug("  {}", ep);
        }
        discovered.set(true);
    }

    /**
     * Registers discovered endpoint descriptors including allowed HTTP methods.
     * Used for Format B discovery (descriptor objects with method flags).
     */
    private void registerDescriptors(List<EndpointDescriptor> descriptors) {
        for (EndpointDescriptor desc : descriptors) {
            String fullUrl = desc.url.startsWith("http") ? desc.url : baseUrl + desc.url;
            endpoints.put(desc.url, fullUrl);
            if (!desc.methods.isEmpty()) {
                endpointMethods.put(desc.url, desc.methods);
            }
            log.debug("  {} {}", desc.url, desc.methods);
        }
        discovered.set(true);
    }

    /** Simple holder for a discovered endpoint URL + its allowed HTTP methods. */
    record EndpointDescriptor(String url, Set<HttpMethod> methods) {}

    /**
     * Attempts to interpret a CCAPI version value as a list of endpoint
     * descriptor objects ({@code [{url:"...", get:true, ...}, ...]}) and
     * returns the extracted descriptors with HTTP method info.
     * Returns {@code null} if the value does not match this format.
     */
    @SuppressWarnings("unchecked")
    static List<EndpointDescriptor> extractDescriptors(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object first = list.getFirst();
        if (!(first instanceof Map)) {
            return null;
        }
        List<EndpointDescriptor> result = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object url = map.get("url");
                if (url instanceof String s) {
                    Set<HttpMethod> methods = new HashSet<>();
                    if (Boolean.TRUE.equals(map.get("get")))    methods.add(HttpMethod.GET);
                    if (Boolean.TRUE.equals(map.get("post")))   methods.add(HttpMethod.POST);
                    if (Boolean.TRUE.equals(map.get("put")))    methods.add(HttpMethod.PUT);
                    if (Boolean.TRUE.equals(map.get("delete"))) methods.add(HttpMethod.DELETE);
                    result.add(new EndpointDescriptor(s, methods));
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Kept for backward compatibility with existing callers.
     * Delegates to {@link #extractDescriptors(Object)} and returns only the URLs.
     */
    @SuppressWarnings("unchecked")
    static List<String> extractDescriptorUrls(Object value) {
        List<EndpointDescriptor> descriptors = extractDescriptors(value);
        if (descriptors == null) return null;
        return descriptors.stream().map(EndpointDescriptor::url).toList();
    }

    /**
     * Returns {@code true} if the given endpoint (matched by suffix) was
     * discovered with the specified HTTP method allowed.  If no method info
     * was recorded (Format A discovery), returns the supplied default.
     */
    boolean endpointAllows(String suffix, HttpMethod method, boolean defaultValue) {
        if (!discovered.get()) discoverEndpoints();
        for (Map.Entry<String, Set<HttpMethod>> entry : endpointMethods.entrySet()) {
            if (entry.getKey().endsWith(suffix) || entry.getKey().contains(suffix)) {
                return entry.getValue().contains(method);
            }
        }
        return defaultValue;
    }

    /**
     * Extracts a {@code String} value from a CCAPI response entry that may be
     * either a plain {@code String} or a {@code List<String>} (some camera
     * firmwares wrap the URL in a JSON array).  Returns {@code null} if the
     * value cannot be interpreted as a single string.
     */
    @SuppressWarnings("unchecked")
    static String extractString(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object first = list.getFirst();
            if (first instanceof String s) {
                return s;
            }
        }
        return null;
    }

    /**
     * Finds a discovered endpoint whose path contains the given suffix.
     * Returns the full URL, or {@code null} if not found.
     */
    String findEndpoint(String suffix) {
        if (!discovered.get()) discoverEndpoints();
        for (Map.Entry<String, String> entry : endpoints.entrySet()) {
            if (entry.getKey().endsWith(suffix) || entry.getKey().contains(suffix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Returns the discovered endpoint URL or falls back to a constructed default.
     */
    String resolveEndpoint(String suffix, String defaultPath) {
        String found = findEndpoint(suffix);
        if (found != null) return found;
        log.debug("Endpoint '{}' not discovered, falling back to {}", suffix, defaultPath);
        return baseUrl + defaultPath;
    }

    // ── CameraBackend implementation ────────────────────────────────────────

    @Override
    public CameraInfo getInfo() {
        String url = resolveEndpoint("deviceinformation", "/ccapi/ver100/deviceinformation");
        try {
            DeviceInformation info = restTemplate.getForObject(url, DeviceInformation.class);
            String model = (info != null && info.productname() != null)
                    ? info.productname() : DEFAULT_MODEL;
            String serial = (info != null && info.serialnumber() != null)
                    ? info.serialnumber() : "unknown";
            String firmware = (info != null && info.firmwareversion() != null)
                    ? info.firmwareversion() : "unknown";
            String macaddress = (info != null && info.macaddress() != null)
                    ? info.macaddress() : null;
            return new CameraInfo(
                    "ccapi-" + serial,
                    "Canon Camera",
                    model, "ccapi", baseUrl, firmware);
        } catch (RestClientException e) {
            log.warn("CCAPI deviceinformation unavailable ({}), using defaults", e.getMessage());
            return new CameraInfo("ccapi-camera", "Canon Camera", DEFAULT_MODEL, "ccapi", baseUrl, "unknown");
        }
    }

    @Override
    public LiveViewFrame getLiveView() {
        // Prefer /liveview/flip (single JPEG frame) over /liveview (MJPEG stream).
        // The camera returns 503 "Live view not started" if liveview has not been
        // enabled yet — callers should invoke startLiveView() first.
        String url = findEndpoint("liveview/flip");
        if (url == null) url = resolveEndpoint("liveview", "/ccapi/ver100/shooting/liveview/flip");
        try {
            byte[] data = restTemplate.getForObject(url, byte[].class);
            if (data == null) {
                data = new byte[0];
            }
            return new LiveViewFrame(data, 960, 640, Instant.now(), MediaType.IMAGE_JPEG_VALUE);
        } catch (RestClientException e) {
            throw new CameraException("Failed to get live view from " + url + ": " + e.getMessage(), e);
        }
    }

    /**
     * Starts the remote live-view output on the camera.
     *
     * <p>Per the CCAPI Reference (§4.11.1) this must be called before
     * {@code GET /shooting/liveview/flip} will return image data.
     *
     * @param size live-view output size: {@code "small"}, {@code "medium"},
     *             or {@code "off"} to stop
     */
    public void startLiveView(String size) {
        String url = resolveEndpoint("/shooting/liveview",
                "/ccapi/ver100/shooting/liveview");
        // Only match the base /liveview endpoint, not /liveview/flip etc.
        // findEndpoint matches by suffix/contains, so search for exact base path
        String found = null;
        for (Map.Entry<String, String> entry : endpoints.entrySet()) {
            if (entry.getKey().endsWith("/shooting/liveview")) {
                found = entry.getValue();
                break;
            }
        }
        if (found != null) url = found;

        Map<String, Object> body = Map.of(
                "liveviewsize", size,
                "cameradisplay", "on");
        try {
            ccapiPost(url, body);
            log.info("Live view started (size={})", size);
        } catch (RestClientException e) {
            throw new CameraException(
                    "Failed to start live view via " + url + ": " + e.getMessage(), e);
        }
    }

    @Override
    public CaptureResult capture() {
        String url = resolveEndpoint("shutterbutton", "/ccapi/ver100/shooting/control/shutterbutton");
        try {
            // CCAPI spec §4.8.1: POST /shooting/control/shutterbutton
            // Body: {"af": true/false}
            // We disable AF so the pre-set focus position is preserved between
            // stack frames.
            ccapiPost(url, Map.of("af", false));
            String imageId = UUID.randomUUID().toString();
            return new CaptureResult(
                    imageId,
                    "capture-" + imageId + ".jpg",
                    url,
                    Instant.now(),
                    focusPosition.get(),
                    0L,
                    null // images are stored on the camera card; downloaded separately
            );
        } catch (RestClientException e) {
            throw new CameraException("Failed to capture image via " + url + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void setFocus(FocusCommand command) {
        String direction = command.direction() == FocusCommand.Direction.NEAR ? "near" : "far";
        String size = toMfDriveSize(command.steps());

        // Try discovered endpoints in order of preference:
        // 1. /shooting/control/af (CCAPI §4.8.8)
        //    Body: {"action": "on", "lensdrivecomposite": "near_small"}
        //    Use PUT if the endpoint descriptor explicitly allows it, otherwise POST.
        // 2. /shooting/liveview/remocon (fallback for cameras without /af)
        //    Body: {"direction": "far", "magnitude": "medium"}
        // 3. Log a warning if neither is available.

        String afUrl = findEndpoint("/shooting/control/af");
        if (afUrl != null) {
            Map<String, Object> body = Map.of(
                    "action", "on",
                    "lensdrivecomposite", direction + "_" + size);
            try {
                if (endpointAllows("/shooting/control/af", HttpMethod.PUT, false)) {
                    restTemplate.put(afUrl, ccapiEntity(body));
                } else {
                    ccapiPost(afUrl, body);
                }
            } catch (RestClientException e) {
                throw new CameraException(
                        "Failed to trigger AF via " + afUrl + ": " + e.getMessage(), e);
            }
        } else {
            // Camera without /af — fall back to remocon for manual focus drive.
            String remoconUrl = findEndpoint("/shooting/liveview/remocon");
            if (remoconUrl != null) {
                Map<String, Object> body = Map.of(
                        "direction", direction,
                        "magnitude", size);
                try {
                    ccapiPost(remoconUrl, body);
                } catch (RestClientException e) {
                    throw new CameraException(
                            "Failed to drive focus via " + remoconUrl + ": " + e.getMessage(), e);
                }
            } else {
                log.warn("No focus-control endpoint discovered on camera {}. "
                        + "Available endpoints: {}. "
                        + "Focus adjustment ignored.",
                        baseUrl, endpoints.keySet());
            }
        }
        // Update tracked position: NEAR decrements, FAR increments
        int delta = command.direction() == FocusCommand.Direction.NEAR
                ? -command.steps() : command.steps();
        focusPosition.addAndGet(delta);
    }

    @Override
    public CameraState getState() {
        int batteryLevel = 100;
        long storageAvailable = 0L;
        boolean connected = false;

        // Battery — look for discovered battery endpoint
        String batteryUrl = resolveEndpoint("battery", "/ccapi/ver100/devicestatus/battery");
        try {
            BatteryStatus battery = restTemplate.getForObject(batteryUrl, BatteryStatus.class);
            if (battery != null && battery.level() != null) {
                batteryLevel = parseBatteryLevel(battery.level());
            }
            connected = true;
        } catch (RestClientException e) {
            log.debug("CCAPI battery status unavailable: {}", e.getMessage());
        }

        // Storage — try discovered /currentstorage, then /storage.
        // Do NOT fall back to a hardcoded URL if neither is discovered —
        // the camera simply may not expose a storage endpoint.
        String storageUrl = findEndpoint("currentstorage");
        if (storageUrl == null) storageUrl = findEndpoint("storage");
        if (storageUrl != null) {
            try {
                StorageStatus storage = restTemplate.getForObject(storageUrl, StorageStatus.class);
                if (storage != null && storage.spaceBytes() != null) {
                    storageAvailable = storage.spaceBytes();
                }
                connected = true;
            } catch (RestClientException e) {
                log.debug("CCAPI storage status unavailable: {}", e.getMessage());
            }
        } else {
            log.debug("No storage endpoint discovered — skipping storage status");
        }

        return new CameraState(
                connected, batteryLevel, storageAvailable, focusPosition.get(),
                "auto", "auto", "auto", false
        );
    }

    @Override
    public boolean supports(Operation operation) {
        return switch (operation) {
            case LIVE_VIEW -> findEndpoint("liveview") != null;
            case CAPTURE -> findEndpoint("shutterbutton") != null;
            case FOCUS_CONTROL -> findEndpoint("/shooting/control/drivefocus") != null
                    || findEndpoint("/shooting/control/af") != null;
            case SETTINGS_READ -> true;
            case ZOOM_CONTROL, SETTINGS_WRITE -> false;
        };
    }

    // ── test-visible accessors ──────────────────────────────────────────────

    /** Returns an unmodifiable view of the discovered endpoints map. */
    public Map<String, String> getDiscoveredEndpoints() {
        if (!discovered.get()) discoverEndpoints();
        return Map.copyOf(endpoints);
    }

    /** Returns an unmodifiable view of the discovered HTTP methods per endpoint. */
    public Map<String, Set<HttpMethod>> getDiscoveredEndpointMethods() {
        if (!discovered.get()) discoverEndpoints();
        return Map.copyOf(endpointMethods);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Wraps a request body in an {@link HttpEntity} with a minimal header set.
     * Canon CCAPI rejects Spring's default multi-value Accept header.
     */
    private static <T> HttpEntity<T> ccapiEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(body, headers);
    }

    /**
     * Sends a POST request to the given CCAPI URL with the supplied body,
     * using {@link #ccapiEntity(Object)} to set the required headers.
     */
    private void ccapiPost(String url, Object body) {
        restTemplate.postForObject(url, ccapiEntity(body), String.class);
    }

    /**
     * Maps an abstract step count to a CCAPI focus-drive size string.
     * CCAPI supports "small", "medium", "large".
     */
    static String toMfDriveSize(int steps) {
        if (steps <= 1) return "small";
        if (steps <= 5) return "medium";
        return "large";
    }

    /**
     * Parses a CCAPI battery level string ("high", "middle", "low", "empty",
     * "full") or a numeric percentage string ("80") into an integer 0–100.
     * Returns 100 for null or unrecognised input. Trims whitespace before parsing.
     */
    static int parseBatteryLevel(String level) {
        if (level == null) {
            return 100;
        }
        String normalized = level.trim().toLowerCase();
        return switch (normalized) {
            case "full"   -> 100;
            case "high"   -> 80;
            case "half", "middle" -> 50;
            case "low"    -> 20;
            case "empty"  -> 0;
            default -> {
                try { yield Math.max(0, Math.min(100, Integer.parseInt(normalized))); }
                catch (NumberFormatException e) { yield 100; }
            }
        };
    }

    // ── CCAPI response DTOs ────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeviceInformation(
            @JsonProperty("productname")    String productname,
            @JsonProperty("serialnumber")   String serialnumber,
            @JsonProperty("firmwareversion") String firmwareversion,
            @JsonProperty("macaddress")     String macaddress
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatteryStatus(
            @JsonProperty("name")    String name,
            @JsonProperty("kind")    String kind,
            @JsonProperty("level")   String level,
            @JsonProperty("quality") String quality
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StorageStatus(
            @JsonProperty("name")     String name,
            @JsonProperty("maxsize")  Long maxBytes,
            @JsonProperty("spacesize") Long spaceBytes
    ) {}
}
