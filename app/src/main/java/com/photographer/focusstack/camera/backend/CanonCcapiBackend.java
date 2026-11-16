package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CapturedImageFormat;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.common.camera.ProcessingCaptureProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;

import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
public class CanonCcapiBackend implements CameraBackend, ProcessingCaptureProvider {

    private static final Logger log = LoggerFactory.getLogger(CanonCcapiBackend.class);

    /** Default camera model reported when the CCAPI device-info call is unavailable. */
    static final String DEFAULT_MODEL = "Canon Camera";

    /** Default poll interval when waiting for a new image to appear on the SD card. */
    private static final long DEFAULT_CAPTURE_POLL_INTERVAL_MS = 500L;
    /** Default maximum time to wait for a new image after shutter release. */
    private static final long DEFAULT_CAPTURE_TIMEOUT_MS = 10_000L;

    /** Delay (ms) between half-press and full-press in the 3-phase shutter sequence. */
    private static final long DEFAULT_SHUTTER_HALF_PRESS_DELAY_MS = 300L;
    /** Delay (ms) between full-press and release in the 3-phase shutter sequence. */
    private static final long DEFAULT_SHUTTER_FULL_PRESS_DELAY_MS = 250L;
    /** Delay (ms) after release to allow the image to be written to the SD card. */
    private static final long DEFAULT_SHUTTER_RELEASE_DELAY_MS = 1200L;

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final long capturePollIntervalMs;
    private final long captureTimeoutMs;
    private final long shutterHalfPressDelayMs;
    private final long shutterFullPressDelayMs;
    private final long shutterReleaseDelayMs;

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
        this(baseUrl, restTemplate, DEFAULT_CAPTURE_POLL_INTERVAL_MS, DEFAULT_CAPTURE_TIMEOUT_MS);
    }

    /**
     * Constructor that allows overriding the capture polling behaviour.
     * Use {@code captureTimeoutMs = 0} to disable image download entirely
     * (e.g. in unit tests that only verify the shutter trigger).
     */
    CanonCcapiBackend(String baseUrl, RestTemplate restTemplate,
                       long capturePollIntervalMs, long captureTimeoutMs) {
        this(baseUrl, restTemplate, capturePollIntervalMs, captureTimeoutMs,
                DEFAULT_SHUTTER_HALF_PRESS_DELAY_MS, DEFAULT_SHUTTER_FULL_PRESS_DELAY_MS,
                DEFAULT_SHUTTER_RELEASE_DELAY_MS);
    }

    /**
     * Full constructor that allows overriding all timing parameters.
     * Use all-zero delays for unit tests to avoid actual waits.
     */
    CanonCcapiBackend(String baseUrl, RestTemplate restTemplate,
                       long capturePollIntervalMs, long captureTimeoutMs,
                       long shutterHalfPressDelayMs, long shutterFullPressDelayMs,
                       long shutterReleaseDelayMs) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
        this.capturePollIntervalMs = capturePollIntervalMs;
        this.captureTimeoutMs = captureTimeoutMs;
        this.shutterHalfPressDelayMs = shutterHalfPressDelayMs;
        this.shutterFullPressDelayMs = shutterFullPressDelayMs;
        this.shutterReleaseDelayMs = shutterReleaseDelayMs;
    }

    final String ccapiBaseUrl() {
        return baseUrl;
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
            log.info("Discovering CCAPI endpoints");

            @SuppressWarnings("unchecked")
            Map<String, Object> versions = restTemplate.getForObject(
                    baseUrl + "/ccapi", Map.class);
            if (versions == null || versions.isEmpty()) {
                // Some firmware exposes the API under /ccapi/topurlfordev instead
                // of /ccapi (the "developer" landing endpoint). Try that as a
                // secondary discovery hop before giving up.
                versions = fetchVersionsViaTopUrlFallback();
                if (versions == null || versions.isEmpty()) {
                    log.warn("CCAPI root returned no versions (and topurlfordev fallback also yielded none)");
                    return;
                }
            }
            log.info("CCAPI version groups: {}", versions.size());

            // Pick the highest version (e.g. ver110 > ver100)
            String bestVersion = versions.keySet().stream()
                    .filter(k -> k.startsWith("ver"))
                    .sorted().reduce((a, b) -> b).orElse(null);
            if (bestVersion == null) {
                log.warn("No versioned CCAPI entry found");
                return;
            }

            Object versionValue = versions.get(bestVersion);

            // ── Format B: list of endpoint descriptor objects ────────────
            List<EndpointDescriptor> descriptors = extractDescriptors(versionValue);
            if (descriptors != null) {
                log.info("Using CCAPI catalog with {} endpoint descriptors", descriptors.size());
                registerDescriptors(descriptors);
                return;
            }

            // ── Format A: version root URL (string or single-element list) ─
            String versionRoot = extractString(versionValue);
            if (versionRoot == null) {
                log.warn("Version entry has no usable URL");
                return;
            }
            String versionUrl = versionRoot.startsWith("http")
                    ? versionRoot : baseUrl + versionRoot;
            log.info("Using discovered CCAPI version root");

            @SuppressWarnings("unchecked")
            List<String> eps = restTemplate.getForObject(versionUrl, List.class);
            if (eps == null || eps.isEmpty()) {
                log.warn("CCAPI version root returned no endpoints");
                return;
            }
            log.info("CCAPI discovered {} endpoints via version root", eps.size());
            registerEndpoints(eps);
        } catch (RestClientException e) {
            log.warn("CCAPI endpoint discovery failed ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * Secondary discovery hop: queries {@code GET /ccapi/topurlfordev}, which on
     * some Canon firmwares is the only way to learn the API root URL when the
     * direct {@code /ccapi} call returns nothing useful. The response is
     * expected to contain the same version → URL/descriptor mapping that
     * {@code /ccapi} would have returned.
     *
     * @return a versions map (same shape as {@code GET /ccapi}), or {@code null}
     *         when the fallback is also unavailable or empty
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> fetchVersionsViaTopUrlFallback() {
        try {
            String fallbackUrl = baseUrl + "/ccapi/topurlfordev";
            log.info("Attempting CCAPI fallback discovery");
            Object response = restTemplate.getForObject(fallbackUrl, Object.class);
            if (response == null) {
                return null;
            }
            // Form 1: response is already a versions map (same as /ccapi).
            // Some firmwares may instead return a pointer wrapper such as
            // { "url": "/ccapi" }, so only treat the map as versions when it
            // actually contains version keys (e.g. "ver100", "ver110", ...).
            Map<String, Object> typedResponse = null;
            if (response instanceof Map<?, ?> map && !map.isEmpty()) {
                final Map<String, Object> typed = new java.util.LinkedHashMap<>();
                map.forEach((k, v) -> typed.put(String.valueOf(k), v));
                boolean looksLikeVersionsMap = typed.keySet().stream()
                        .anyMatch(key -> key != null && key.startsWith("ver"));
                if (looksLikeVersionsMap) {
                    return typed;
                }
                typedResponse = typed;
            }
            // Form 2: response is a single URL string pointing at the API root,
            //         which we then GET to obtain the versions map.
            String pointer = null;
            if (response instanceof String s && !s.isBlank()) {
                pointer = s;
            } else if (typedResponse != null
                    && typedResponse.get("url") instanceof String s
                    && !s.isBlank()) {
                pointer = s;
            }
            if (pointer != null) {
                String url = pointer.startsWith("http") ? pointer : baseUrl + pointer;
                Map<String, Object> follow = restTemplate.getForObject(url, Map.class);
                if (follow != null && !follow.isEmpty()) {
                    return follow;
                }
            }
            return null;
        } catch (RestClientException e) {
            log.debug("CCAPI topurlfordev fallback failed: {}", e.getClass().getSimpleName());
            return null;
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
            log.debug("Discovered endpoint methods: {}", desc.methods);
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
                String urlStr = null;
                Object url = map.get("url");
                if (url instanceof String s) {
                    // Prefer "url" field when present (most Canon firmware).
                    urlStr = s;
                } else {
                    // Fall back to "path" field as an alternative (Canon SDK alternative format).
                    // If both exist, "url" wins; this branch is only reached when "url" is absent.
                    Object path = map.get("path");
                    if (path instanceof String p) {
                        urlStr = p;
                    }
                }
                if (urlStr != null) {
                    Set<HttpMethod> methods = new HashSet<>();
                    if (Boolean.TRUE.equals(map.get("get")))    methods.add(HttpMethod.GET);
                    if (Boolean.TRUE.equals(map.get("post")))   methods.add(HttpMethod.POST);
                    if (Boolean.TRUE.equals(map.get("put")))    methods.add(HttpMethod.PUT);
                    if (Boolean.TRUE.equals(map.get("delete"))) methods.add(HttpMethod.DELETE);
                    result.add(new EndpointDescriptor(urlStr, methods));
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
            log.warn("CCAPI deviceinformation unavailable ({}), using defaults", e.getClass().getSimpleName());
            return new CameraInfo("ccapi-camera", "Canon Camera", DEFAULT_MODEL, "ccapi", baseUrl, "unknown");
        }
    }

    /** Tracks whether we have already started live view on this backend instance. */
    private final AtomicBoolean liveViewStarted = new AtomicBoolean(false);
    private volatile String lastProcessingShot;

    @Override
    public LiveViewFrame getLiveView() {
        // Prefer /liveview/flip (single JPEG frame) over /liveview (MJPEG stream).
        String url = findEndpoint("liveview/flip");
        if (url == null) url = resolveEndpoint("liveview", "/ccapi/ver100/shooting/liveview/flip");

        // Per the Canon CCAPI spec (§4.11.1), live view must be started via
        // POST /shooting/liveview before GET /liveview/flip returns image data.
        // Auto-start on first call to avoid requiring callers to manage the
        // live-view lifecycle explicitly.
        if (liveViewStarted.compareAndSet(false, true)) {
            try {
                startLiveView("medium");
                sleepMs(500); // give the camera time to initialise the stream
            } catch (CameraException e) {
                liveViewStarted.set(false);
                log.warn("Failed to auto-start live view: {}", e.getClass().getSimpleName());
            }
        }

        try {
            byte[] data = restTemplate.getForObject(url, byte[].class);
            if (data == null) {
                data = new byte[0];
            }
            return new LiveViewFrame(data, 960, 640, Instant.now(), MediaType.IMAGE_JPEG_VALUE);
        } catch (org.springframework.web.client.HttpServerErrorException.ServiceUnavailable e) {
            // 503 "Live view not started" — the camera requires POST /liveview first.
            // Try to start it and retry once.
            log.info("Live view not started, attempting to start it now...");
            liveViewStarted.set(false);
            try {
                startLiveView("medium");
                liveViewStarted.set(true);
                sleepMs(500);
                byte[] data = restTemplate.getForObject(url, byte[].class);
                if (data == null) data = new byte[0];
                return new LiveViewFrame(data, 960, 640, Instant.now(), MediaType.IMAGE_JPEG_VALUE);
            } catch (RestClientException retryEx) {
                throw new CameraException(
                        "Failed to get live view from " + url + " after auto-start: " + retryEx.getMessage(), retryEx);
            }
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
    public void stopLiveView() {
        // Use exact-match loop (same as startLiveView) to avoid matching
        // sub-paths like /liveview/flip or /liveview/scroll.
        String url = null;
        for (Map.Entry<String, String> entry : endpoints.entrySet()) {
            if (entry.getKey().endsWith("/shooting/liveview")) {
                url = entry.getValue();
                break;
            }
        }
        if (url == null) {
            log.debug("No /shooting/liveview endpoint discovered, cannot stop live view.");
            liveViewStarted.set(false);
            return;
        }
        try {
            ccapiPost(url, Map.of("liveviewsize", "off"));
            log.info("Live view stopped.");
        } catch (RestClientException e) {
            // Camera may already be idle — log and continue.
            log.debug("Failed to stop live view (may already be off): {}", e.getClass().getSimpleName());
        }
        // Reset the flag so getLiveView() will re-start LV on next call.
        liveViewStarted.set(false);
    }

    @Override
    public CaptureResult capture() {
        return capture(false);
    }

    @Override
    public CaptureResult captureForProcessing() {
        return capture(true);
    }

    private CaptureResult capture(boolean preferRaw) {
        // Prefer shutterbutton/manual for the 3-phase sequence (Canon SDK §4.8.2).
        // Fall back to the plain shutterbutton endpoint for older firmware.
        String manualUrl = findEndpoint("shutterbutton/manual");
        String regularUrl = resolveEndpoint("shutterbutton",
                "/ccapi/ver100/shooting/control/shutterbutton");
        String triggerUrl = manualUrl != null ? manualUrl : regularUrl;

        // Stop live view before capture to prevent "Device busy" errors.
        // The camera cannot capture while the live view stream is active.
        stopLiveView();
        sleepMs(250); // Give camera a moment to settle

        try {
            // Record the newest image datetime before triggering the shutter so we
            // can detect the newly written file during the polling phase.
            // Only do this when a contents endpoint was actually discovered — avoids
            // unnecessary network calls on cameras that don't expose contents browsing.
            boolean contentsDiscovered = findEndpoint("contents/sd") != null
                    || findEndpoint("contents") != null;
            String contentsBaseUrl = contentsDiscovered ? resolveContentsBase() : null;
            String preNewestDatetime = contentsDiscovered && !preferRaw
                    ? findNewestImageDatetime(contentsBaseUrl) : null;
            Set<String> previousShots = contentsDiscovered && preferRaw
                    ? listRecentImages(contentsBaseUrl).stream()
                            .map(location -> processingShotKey(imageUrl(contentsBaseUrl, location)))
                            .collect(Collectors.toUnmodifiableSet())
                    : Set.of();

            if (manualUrl != null) {
                // 3-phase shutter sequence per Canon SDK:
                // half_press → full_press → release, with delays between phases.
                ccapiPost(manualUrl, Map.of("action", "half_press", "af", false));
                sleepMs(shutterHalfPressDelayMs);
                ccapiPost(manualUrl, Map.of("action", "full_press", "af", false));
                sleepMs(shutterFullPressDelayMs);
                ccapiPost(manualUrl, Map.of("action", "release", "af", false));
                sleepMs(shutterReleaseDelayMs);
            } else {
                // Legacy single-POST approach for cameras without shutterbutton/manual.
                // We disable AF so the pre-set focus position is preserved between
                // stack frames.
                ccapiPost(regularUrl, Map.of("af", false));
            }

            String imageId = UUID.randomUUID().toString();

            // Try event/polling first (faster), then fall back to DCIM content browsing.
            // DCIM browsing is only attempted when the contents endpoint was discovered;
            // otherwise skip it to avoid adding captureTimeoutMs delay on every frame for
            // cameras that expose shutter control but no contents endpoint.
            DownloadResult download = preferRaw
                    ? downloadProcessingImage(contentsBaseUrl, previousShots)
                    : downloadNewImageViaEventPolling();
            if (download == null && contentsDiscovered && !preferRaw) {
                download = downloadNewImage(contentsBaseUrl, preNewestDatetime);
            }

            byte[] imageData = download != null ? download.data() : null;
            // Use the real filename from the camera (e.g. IMG_1234.CR3, IMG_1234.JPG)
            // instead of a hardcoded .jpg extension, so that content-type detection
            // and downstream RAW processing work correctly.
            String fileName = download != null && download.fileName() != null
                    ? download.fileName()
                    : "capture-" + imageId + ".bin";

            return new CaptureResult(
                    imageId,
                    fileName,
                    triggerUrl,
                    Instant.now(),
                    focusPosition.get(),
                    imageData != null ? (long) imageData.length : 0L,
                    imageData
            );
        } catch (RestClientException e) {
            throw new CameraException("Failed to capture image via " + triggerUrl + ": " + e.getMessage(), e);
        }
    }

    // ── content browse & download ──────────────────────────────────────────

    /**
     * Resolves the base URL for browsing image folders on the camera's SD card.
     *
     * <p>Canon CCAPI places image folders (e.g. {@code 100CANON}) <strong>directly
     * under {@code /contents/sd}</strong> — there is no intermediate {@code /DCIM}
     * directory level as on a physical SD card.  The storage API
     * ({@code /devicestatus/storage}) returns a {@code storagelist[].url} field
     * pointing to the correct base (e.g.
     * {@code http://camera.example:8080/ccapi/ver100/contents/sd}).
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Discovered endpoint containing {@code contents/sd}</li>
     *   <li>URL from the {@code /devicestatus/storage} storagelist</li>
     *   <li>Well-known fallback {@code /ccapi/ver100/contents/sd}</li>
     * </ol>
     */
    private String resolveContentsBase() {
        // 1. Try discovered endpoint map
        String found = findEndpoint("contents/sd");
        if (found != null) {
            return found;
        }
        // Also check for a "contents" endpoint and append "/sd"
        found = findEndpoint("contents");
        if (found != null) {
            return found.endsWith("/") ? found + "sd" : found + "/sd";
        }
        // 2. Try reading the storage API for the URL
        try {
            @SuppressWarnings("unchecked")
            var storage = restTemplate.getForObject(
                    resolveEndpoint("storage", "/ccapi/ver100/devicestatus/storage"),
                    Map.class);
            if (storage != null) {
                @SuppressWarnings("unchecked")
                var list = (List<Map<String, Object>>) storage.get("storagelist");
                if (list != null && !list.isEmpty()) {
                    String url = (String) list.get(0).get("url");
                    if (url != null) return url;
                }
            }
        } catch (Exception e) {
            log.debug("Could not read storage URL from /devicestatus/storage: {}", e.getClass().getSimpleName());
        }
        // 3. Well-known fallback (no /DCIM — Canon CCAPI doesn't use it)
        return baseUrl + "/ccapi/ver100/contents/sd";
    }

    /**
     * Returns the {@code datetime} string of the newest image currently on the
     * SD card, or {@code null} if no images are found or the contents endpoint
     * is unavailable.
     */
    private String findNewestImageDatetime(String dcimUrl) {
        try {
            ImageLocation loc = findNewestImage(dcimUrl);
            return loc != null ? loc.entry().datetime() : null;
        } catch (Exception e) {
            log.debug("Could not read pre-capture contents listing: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Polls the SD card contents until a new image appears (newer than
     * {@code preNewestDatetime}) and downloads it.
     *
     * <p>Returns {@code null} with a warning if no new image appears within
     * {@link #captureTimeoutMs} milliseconds (graceful degradation).
     * Returns {@code null} immediately if {@link #captureTimeoutMs} is {@code 0}.
     */
    private DownloadResult downloadNewImage(String dcimUrl, String preNewestDatetime) {
        if (captureTimeoutMs <= 0) {
            return null;
        }
        long deadline = System.currentTimeMillis() + captureTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                ImageLocation newest = findNewestImage(dcimUrl);
                if (newest != null && isNewerThan(newest.entry().datetime(), preNewestDatetime)) {
                    String imageUrl = dcimUrl + "/" + newest.folderName()
                            + "/" + newest.entry().name();
                    byte[] data = restTemplate.getForObject(imageUrl, byte[].class);
                    if (data != null && data.length > 0) {
                        String fileName = newest.entry().name();
                        log.info("Downloaded {} bytes from camera storage", data.length);
                        return new DownloadResult(fileName, data);
                    }
                }
            } catch (Exception e) {
                log.debug("Error while polling SD card for new image: {}", e.getClass().getSimpleName());
            }
            try {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                Thread.sleep(capturePollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("Timed out after {}ms waiting for new image on camera", captureTimeoutMs);
        return null;
    }

    /**
     * Finds the newest image file (by datetime) across all DCIM sub-folders.
     * Returns {@code null} if the camera has no images or the contents
     * endpoint is unavailable.
     */
    private ImageLocation findNewestImage(String dcimUrl) {
        return listRecentImages(dcimUrl).stream()
                .max(Comparator.comparing(
                        location -> parseOffsetDateTimeOrNull(location.entry().datetime()),
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private List<ImageLocation> listRecentImages(String dcimUrl) {
        ContentListing folderListing = listContents(dcimUrl);
        if (folderListing == null || folderListing.dir() == null || folderListing.dir().isEmpty()) {
            return List.of();
        }
        // Sort folders descending by name so the highest-numbered folder is first.
        List<ContentEntry> folders = folderListing.dir().stream()
                .filter(e -> "D".equals(e.attrib()))
                .sorted(Comparator.comparing(ContentEntry::name).reversed())
                .toList();
        for (ContentEntry folder : folders) {
            String folderUrl = dcimUrl + "/" + folder.name();
            ContentListing fileListing = listContents(folderUrl);
            if (fileListing == null || fileListing.dir() == null) continue;
            List<ImageLocation> images = fileListing.dir().stream()
                    .filter(e -> "A".equals(e.attrib()) && isImageFile(e.name()))
                    .filter(e -> e.datetime() != null)
                    .map(entry -> new ImageLocation(folder.name(), entry))
                    .toList();
            if (!images.isEmpty()) {
                return images;
            }
        }
        return List.of();
    }

    private DownloadResult downloadProcessingImage(String contentsUrl, Set<String> previousShots) {
        if (captureTimeoutMs <= 0) {
            return null;
        }
        String pollingUrl = findEndpoint("event/polling");
        if (pollingUrl == null && contentsUrl == null) {
            return null;
        }
        long deadline = System.currentTimeMillis() + captureTimeoutMs;
        long pairDeadline = deadline;
        Map<String, String> candidates = new LinkedHashMap<>();
        while (!Thread.currentThread().isInterrupted()) {
            if (pollingUrl != null) {
                addProcessingCandidates(candidates, addedImageUrls(pollingUrl), previousShots);
            }
            if (contentsUrl != null && candidates.values().stream().noneMatch(CapturedImageFormat::isCr3)) {
                List<String> listed = listRecentImages(contentsUrl).stream()
                        .sorted(Comparator.comparing(
                                (ImageLocation location) -> parseOffsetDateTimeOrNull(location.entry().datetime()),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .map(location -> imageUrl(contentsUrl, location))
                        .toList();
                addProcessingCandidates(candidates, listed, previousShots);
            }

            long now = System.currentTimeMillis();
            if (!candidates.isEmpty()) {
                pairDeadline = Math.min(pairDeadline, now + CapturedImageFormat.PAIRED_FILE_WAIT.toMillis());
                boolean tryFallback = now >= pairDeadline || candidates.size() > 1
                        && candidates.values().stream().anyMatch(CapturedImageFormat::isCanonRaw);
                List<String> ordered = candidates.values().stream()
                        .filter(url -> tryFallback || CapturedImageFormat.isCr3(url))
                        .sorted(Comparator.comparingInt(CapturedImageFormat::processingPriority).reversed())
                        .toList();
                for (String url : ordered) {
                    DownloadResult download = downloadProcessingCandidate(url);
                    if (download != null) {
                        lastProcessingShot = processingShotKey(url);
                        return download;
                    }
                }
            }
            if (now >= deadline || now >= pairDeadline) {
                break;
            }
            sleepMs(Math.max(1L, Math.min(capturePollIntervalMs, pairDeadline - now)));
        }
        return null;
    }

    private List<String> addedImageUrls(String pollingUrl) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(pollingUrl, Map.class);
            return response == null ? List.of() : extractContentUrls(response.get("addedcontents"));
        } catch (RestClientException e) {
            log.debug("event/polling unavailable: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private void addProcessingCandidates(
            Map<String, String> candidates, List<String> paths, Set<String> previousShots) {
        URI origin = URI.create(baseUrl);
        for (String path : paths) {
            URI url;
            try {
                url = origin.resolve(path);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            if (!origin.getScheme().equalsIgnoreCase(url.getScheme())
                    || !origin.getHost().equalsIgnoreCase(url.getHost())
                    || port(origin) != port(url)
                    || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                    || url.getPath() == null || !url.getPath().startsWith("/ccapi/")
                    || !url.getPath().equals(url.normalize().getPath())
                    || !url.getPath().equals(url.getRawPath())) {
                continue;
            }
            String candidate = origin.resolve(url.getRawPath()).toString();
            String pathInStorage = mediaPath(candidate);
            String shot = CapturedImageFormat.shotKey(pathInStorage);
            if (previousShots.contains(shot) || shot.equals(lastProcessingShot)) {
                continue;
            }
            if (candidates.isEmpty()
                    || shot.equals(CapturedImageFormat.shotKey(candidates.keySet().iterator().next()))) {
                candidates.putIfAbsent(pathInStorage, candidate);
            }
        }
    }

    private static int port(URI url) {
        if (url.getPort() >= 0) {
            return url.getPort();
        }
        return "https".equalsIgnoreCase(url.getScheme()) ? 443 : 80;
    }

    private static String mediaPath(String url) {
        int contents = url.indexOf("/contents/");
        return contents < 0 ? url : url.substring(contents);
    }

    private static String processingShotKey(String url) {
        return CapturedImageFormat.shotKey(mediaPath(url));
    }

    private static String imageUrl(String contentsUrl, ImageLocation location) {
        return contentsUrl + "/" + location.folderName() + "/" + location.entry().name();
    }

    private DownloadResult downloadProcessingCandidate(String url) {
        try {
            byte[] data = restTemplate.getForObject(url, byte[].class);
            return data == null || data.length == 0
                    ? null : new DownloadResult(extractFileNameFromUrl(url), data);
        } catch (RestClientException exception) {
            log.debug("Camera image download failed ({})", exception.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Parses an ISO-8601 offset datetime string, returning {@code null} if parsing fails.
     * Used so that {@code max()} comparisons are based on actual instants rather than
     * raw strings (which can sort incorrectly when timezone offsets differ).
     */
    private static OffsetDateTime parseOffsetDateTimeOrNull(String dt) {
        if (dt == null) return null;
        try {
            return OffsetDateTime.parse(dt);
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches a CCAPI directory listing, returning {@code null} on any error. */
    private ContentListing listContents(String url) {
        try {
            return restTemplate.getForObject(url, ContentListing.class);
        } catch (RestClientException e) {
            log.debug("Failed to list camera storage ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Attempts to find and download a newly added image by polling the
     * {@code /event/polling} endpoint.  Returns {@code null} immediately if
     * {@link #captureTimeoutMs} is {@code 0} or the endpoint is not discovered.
     *
     * <p>The polling response is searched recursively for URLs that contain
     * {@code /contents/} and end with a recognised image extension.
     */
    private DownloadResult downloadNewImageViaEventPolling() {
        if (captureTimeoutMs <= 0) {
            return null;
        }
        String pollingUrl = findEndpoint("event/polling");
        if (pollingUrl == null) {
            return null;
        }
        // Retry loop: Canon cameras write the image file asynchronously after the
        // shutter fires. The first event/polling call may return an empty response if
        // the file is still being written to the SD card. We poll repeatedly until a
        // content URL appears or the timeout expires.
        long deadline = System.currentTimeMillis() + captureTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.getForObject(pollingUrl, Map.class);
                if (response != null && !response.isEmpty()) {
                    List<String> contentUrls = extractContentUrls(response);
                    for (String contentUrl : contentUrls) {
                        String fullUrl = contentUrl.startsWith("http") ? contentUrl : baseUrl + contentUrl;
                        try {
                            byte[] data = restTemplate.getForObject(fullUrl, byte[].class);
                            if (data != null && data.length > 0) {
                                String fileName = extractFileNameFromUrl(fullUrl);
                                log.info("Downloaded {} bytes via event polling", data.length);
                                return new DownloadResult(fileName, data);
                            }
                        } catch (RestClientException e) {
                            log.debug("Event image download failed ({})", e.getClass().getSimpleName());
                        }
                    }
                }
            } catch (RestClientException e) {
                log.debug("event/polling unavailable: {}", e.getClass().getSimpleName());
            }
            try {
                if (Thread.currentThread().isInterrupted()) break;
                Thread.sleep(capturePollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /**
     * Recursively searches a CCAPI response object for URLs that look like
     * content paths (contain {@code /contents/} and end with a recognised image
     * extension).
     */
    @SuppressWarnings("unchecked")
    static List<String> extractContentUrls(Object node) {
        List<String> result = new java.util.ArrayList<>();
        if (node instanceof String s) {
            if (s.contains("/contents/") && isImageFile(s)) {
                result.add(s);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                result.addAll(extractContentUrls(item));
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                result.addAll(extractContentUrls(value));
            }
        }
        return result;
    }

    /**
     * Sleeps for the specified number of milliseconds.
     * Returns immediately if {@code ms <= 0}.
     * Interrupts are propagated as a {@link CameraException}.
     */
    private void sleepMs(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CameraException("Interrupted during shutter sequence", e);
        }
    }

    /** Returns {@code true} for JPEG and Canon RAW file extensions. */
    private static boolean isImageFile(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase();
        return upper.endsWith(".JPG") || upper.endsWith(".JPEG")
                || upper.endsWith(".CR2") || upper.endsWith(".CR3");
    }

    /** Extracts the filename from a URL path (e.g. {@code /contents/sd/100CANON/IMG_1234.CR3} → {@code IMG_1234.CR3}). */
    private static String extractFileNameFromUrl(String url) {
        if (url == null) return null;
        // Strip query string
        int queryIdx = url.indexOf('?');
        String path = queryIdx >= 0 ? url.substring(0, queryIdx) : url;
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * Returns {@code true} if {@code dt1} is strictly after {@code dt2} when
     * both are parsed as ISO-8601 offset date-times.
     * Equal timestamps return {@code false}.
     * If {@code dt2} is {@code null} any non-null {@code dt1} is considered
     * "newer" (no prior image existed before this capture).
     * Falls back to lexicographic comparison if parsing fails.
     */
    static boolean isNewerThan(String dt1, String dt2) {
        if (dt1 == null) return false;
        if (dt2 == null) return true;
        try {
            return OffsetDateTime.parse(dt1).isAfter(OffsetDateTime.parse(dt2));
        } catch (Exception e) {
            // ISO 8601 strings sort correctly lexicographically as a fallback.
            return dt1.compareTo(dt2) > 0;
        }
    }

    /** Holds the DCIM folder name and file entry for a located image. */
    record ImageLocation(String folderName, ContentEntry entry) {}

    /** Holds downloaded image bytes together with the original filename from the camera. */
    record DownloadResult(String fileName, byte[] data) {}

    @Override
    public void setFocus(FocusCommand command) {
        String direction = command.direction() == FocusCommand.Direction.NEAR ? "near" : "far";
        String magnitude = toDriveFocusMagnitude(command.steps());

        // Per the Canon CCAPI SDK, manual focus drive is exclusively handled by:
        //   POST /shooting/control/drivefocus  →  {"value": "near1"} | {"value": "far2"} etc.
        // The allowed values are: near1, near2, near3, far1, far2, far3.
        //
        // NOTE: /shooting/control/af is for autofocus (start/stop), NOT manual
        // focus drive.  Its body is {"action": "start"} or {"action": "stop"}.
        // Using it for manual lens drive would be semantically incorrect.

        String driveFocusUrl = findEndpoint("/shooting/control/drivefocus");
        if (driveFocusUrl != null) {
            try {
                ccapiPost(driveFocusUrl, Map.of("value", direction + magnitude));
            } catch (RestClientException e) {
                throw new CameraException(
                        "Failed to drive focus via " + driveFocusUrl + ": " + e.getMessage(), e);
            }
        } else {
            log.warn("Camera did not advertise manual focus drive");
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

        // Battery — pro bodies (R3, R5+BG-R10, ...) expose /devicestatus/batterylist
        // returning multiple battery entries; single-battery bodies use
        // /devicestatus/battery. When batterylist is present we use it
        // exclusively and report the *worst* (lowest) level so a near-empty
        // grip battery is not masked by a full body battery.
        String batteryListUrl = findEndpoint("batterylist");
        if (batteryListUrl != null) {
            try {
                BatteryListStatus list = restTemplate.getForObject(batteryListUrl, BatteryListStatus.class);
                // The REST call returning successfully (no exception) is itself
                // proof that the body is reachable, so report connected even if
                // the payload contains no parseable battery levels.
                connected = true;
                Integer worst = worstBatteryLevel(list);
                if (worst != null) {
                    batteryLevel = worst;
                }
            } catch (RestClientException e) {
                log.debug("CCAPI batterylist unavailable: {}", e.getClass().getSimpleName());
            }
        } else {
            // Single-battery body: query /devicestatus/battery directly.
            String batteryUrl = resolveEndpoint("battery", "/ccapi/ver100/devicestatus/battery");
            try {
                BatteryStatus battery = restTemplate.getForObject(batteryUrl, BatteryStatus.class);
                if (battery != null && battery.level() != null) {
                    batteryLevel = parseBatteryLevel(battery.level());
                }
                connected = true;
            } catch (RestClientException e) {
                log.debug("CCAPI battery status unavailable: {}", e.getClass().getSimpleName());
            }
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
                log.debug("CCAPI storage status unavailable: {}", e.getClass().getSimpleName());
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
            case FOCUS_CONTROL -> findEndpoint("/shooting/control/drivefocus") != null;
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
     * Wraps a request body in an {@link HttpEntity} with the minimal header set
     * that the Canon CCAPI embedded HTTP server accepts.
     *
     * <p>The Canon SDK sample ({@code HttpCommunication.java}) only sets
     * {@code Accept-Encoding: ""} and does <strong>not</strong> send
     * {@code Accept} or explicit {@code Content-Type} headers.  The camera's
     * HTTP server rejects unknown request headers with
     * {@code 400 "Illegal request header"}, so we must keep headers to the
     * absolute minimum.
     *
     * <p>We still set {@code Content-Type: application/json} because Spring's
     * {@link RestTemplate} needs it to serialise the body correctly, and the
     * camera accepts it.  The {@code Accept} header is intentionally omitted.
     */
    protected static HttpEntity<byte[]> ccapiEntity(Object body) {
        // Serialize to JSON bytes manually — this prevents Spring's
        // MappingJackson2HttpMessageConverter from being involved and adding
        // "charset=UTF-8" to the Content-Type, which the Canon camera rejects.
        byte[] jsonBytes;
        if (body instanceof byte[]) {
            jsonBytes = (byte[]) body;
        } else {
            try {
                jsonBytes = JSON_MAPPER.writeValueAsBytes(body);
            } catch (Exception e) {
                throw new CameraException("Failed to serialise CCAPI request body: " + e.getMessage(), e);
            }
        }
        HttpHeaders headers = new HttpHeaders();
        // No Accept, no Accept-Encoding — match the Canon SDK sample.
        // Content-Type is omitted too; the Canon camera does not require it
        // and rejects header values it doesn't understand.
        return new HttpEntity<>(jsonBytes, headers);
    }

    /** Shared Jackson mapper for CCAPI request serialisation. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Maximum number of retry attempts when the camera reports "Device busy" (503). */
    private static final int DEVICE_BUSY_MAX_RETRIES = 5;

    /** Initial backoff in milliseconds before the first retry on "Device busy". */
    private static final long DEVICE_BUSY_INITIAL_BACKOFF_MS = 500;

    /**
     * Sends a POST request to the given CCAPI URL with the supplied body,
     * using {@link #ccapiEntity(Object)} to set the required headers.
     *
     * <p>The body is pre-serialised to raw JSON bytes so that the only header
     * the Canon camera sees is {@code Content-Length} (added automatically by
     * the HTTP client).  This matches the Canon SDK sample which writes raw
     * bytes via {@code OutputStream} without setting any custom headers.
     *
     * <p>If the camera responds with {@code 503 Service Unavailable} and a
     * {@code "Device busy"} message, the request is retried up to
     * {@value #DEVICE_BUSY_MAX_RETRIES} times with exponential backoff
     * starting at {@value #DEVICE_BUSY_INITIAL_BACKOFF_MS}&nbsp;ms.
     *
     * <p>The method is protected so endpoint-catalog specializations can reuse
     * the exact same wire and retry contract instead of maintaining a second copy.
     */
    protected final void ccapiPost(String url, Object body) {
        long backoffMs = DEVICE_BUSY_INITIAL_BACKOFF_MS;
        for (int attempt = 0; ; attempt++) {
            try {
                ccapiPostOnce(url, body);
                return;
            } catch (org.springframework.web.client.HttpServerErrorException.ServiceUnavailable e) {
                boolean deviceBusy = e.getResponseBodyAsString().contains("Device busy");
                if (!deviceBusy || attempt >= DEVICE_BUSY_MAX_RETRIES) {
                    throw e;
                }
                log.warn("Camera busy after POST; retrying in {} ms (attempt {}/{})", backoffMs, attempt + 1, DEVICE_BUSY_MAX_RETRIES);
                sleepMs(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 5_000);
            }
        }
    }

    /** Sends the raw JSON POST without an application-level busy retry. */
    protected final void ccapiPostOnce(String url, Object body) {
        restTemplate.exchange(url, HttpMethod.POST, ccapiEntity(body), String.class);
    }

    /**
     * Maps an abstract step count to a {@code drivefocus} magnitude string
     * as expected by the Canon SDK {@code /shooting/control/drivefocus} endpoint.
     * Returns "1" (small), "2" (medium), or "3" (large).
     */
    static String toDriveFocusMagnitude(int steps) {
        if (steps <= 1) return "1";
        if (steps <= 5) return "2";
        return "3";
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

    /**
     * Returns the lowest battery level (0–100) reported in a CCAPI
     * {@code /devicestatus/batterylist} response, or {@code null} when the
     * list is missing or contains no usable entries.
     *
     * <p>Used by pro bodies that carry multiple batteries (R3 dual cells,
     * R5+BG-R10 grip): the camera may have one full and one near-empty
     * battery, and reporting the worst level lets users avoid surprise
     * shutdowns mid-session.</p>
     */
    static Integer worstBatteryLevel(BatteryListStatus list) {
        if (list == null || list.batterylist() == null || list.batterylist().isEmpty()) {
            return null;
        }
        return list.batterylist().stream()
                .filter(b -> b != null && b.level() != null)
                .map(b -> parseBatteryLevel(b.level()))
                .min(Integer::compareTo)
                .orElse(null);
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

    /**
     * CCAPI {@code /devicestatus/batterylist} response wrapper used by pro
     * bodies (R3, R5+BG-R10, ...). Each {@link BatteryStatus} entry describes
     * one of the camera's batteries; {@link #worstBatteryLevel(BatteryListStatus)}
     * collapses the list to the worst (lowest) reported level.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatteryListStatus(
            @JsonProperty("batterylist") List<BatteryStatus> batterylist
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StorageStatus(
            @JsonProperty("name")     String name,
            @JsonProperty("maxsize")  Long maxBytes,
            @JsonProperty("spacesize") Long spaceBytes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentEntry(
            @JsonProperty("name")     String name,
            @JsonProperty("size")     Long size,
            @JsonProperty("datetime") String datetime,
            @JsonProperty("attrib")   String attrib
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentListing(
            @JsonProperty("dir") List<ContentEntry> dir
    ) {}
}
