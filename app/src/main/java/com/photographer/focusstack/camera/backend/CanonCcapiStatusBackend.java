package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CameraStatusProvider;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Canon CCAPI backend with specification-correct rich status and endpoint mapping.
 *
 * <p>The original backend exposes only the aggregate {@link CameraState} contract.
 * This specialization additionally implements {@link CameraStatusProvider} and
 * derives the legacy state from the rich battery and storage snapshots.</p>
 *
 * <p>Endpoint lookup uses {@link CcapiEndpointCatalog}, retains every advertised
 * API version and matches paths exactly. The same catalog backs inherited capture,
 * focus, live-view and media operations, so the production backend has one
 * authoritative capability source instead of a separate substring-based cache.</p>
 */
public class CanonCcapiStatusBackend extends CanonCcapiBackend implements CameraStatusProvider {

    private static final Logger log = LoggerFactory.getLogger(CanonCcapiStatusBackend.class);
    private static final String STORAGE_SUFFIX = "/devicestatus/storage";
    private static final String BATTERY_SUFFIX = "/devicestatus/battery";
    private static final String BATTERY_LIST_SUFFIX = "/devicestatus/batterylist";
    private static final String LIVE_VIEW_SUFFIX = "/shooting/liveview";
    private static final String VERSION_ROOT_PREFIX = "/ccapi/ver";

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final AtomicInteger trackedFocusPosition = new AtomicInteger();
    private volatile CcapiEndpointCatalog endpointCatalog;

    public CanonCcapiStatusBackend(String baseUrl, RestTemplate restTemplate) {
        super(baseUrl, restTemplate);
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    }

    @Override
    public BatteryInfo getBatteryInfo() {
        return readBattery().value();
    }

    @Override
    public StorageInfo getStorageInfo() {
        return readStorage().value();
    }

    @Override
    public CameraState getState() {
        ReadResult<BatteryInfo> battery = readBattery();
        ReadResult<StorageInfo> storage = readStorage();
        return new CameraState(
                battery.reachable() || storage.reachable(),
                battery.value().percent(),
                storage.value().totalFreeBytes(),
                trackedFocusPosition.get(),
                "auto",
                "auto",
                "auto",
                false
        );
    }

    @Override
    public void setFocus(FocusCommand command) {
        super.setFocus(command);
        int delta = command.direction() == FocusCommand.Direction.NEAR
                ? -command.steps() : command.steps();
        trackedFocusPosition.addAndGet(delta);
    }

    /** Routes inherited discovery through the shared immutable catalog. */
    @Override
    void discoverEndpoints() {
        endpointCatalog();
    }

    /**
     * Resolves the highest exact endpoint that supports the operation's required
     * method. Methodless legacy lists remain usable because their metadata is unknown.
     */
    @Override
    String findEndpoint(String suffix) {
        CcapiEndpointCatalog catalog = endpointCatalog();
        HttpMethod requiredMethod = requiredMethod(suffix);
        if (requiredMethod != null) {
            return catalog.findExactPath(suffix, requiredMethod)
                    .map(CcapiEndpointCatalog.Endpoint::url)
                    .orElse(null);
        }
        return catalog.findExactPath(suffix)
                .map(CcapiEndpointCatalog.Endpoint::url)
                .orElse(null);
    }

    /** Applies advertised method metadata without guessing when metadata is absent. */
    @Override
    boolean endpointAllows(String suffix, HttpMethod method, boolean defaultValue) {
        CcapiEndpointCatalog catalog = endpointCatalog();
        CcapiEndpointCatalog.Endpoint exact = catalog.findExactPath(suffix).orElse(null);
        if (exact == null) {
            return defaultValue;
        }
        CcapiEndpointCatalog.Endpoint compatible = catalog.findExactPath(suffix, method)
                .orElse(null);
        if (compatible == null) {
            return false;
        }
        return compatible.methodMetadataKnown() || defaultValue;
    }

    /**
     * Uses the exact stable suffix encoded in the compatibility path. If Canon
     * advertises that endpoint but explicitly rejects the required method, fail as
     * unsupported instead of silently reconstructing and invoking the same path.
     */
    @Override
    String resolveEndpoint(String suffix, String defaultPath) {
        String exactSuffix = suffixFromDefaultPath(defaultPath, suffix);
        CcapiEndpointCatalog catalog = endpointCatalog();
        HttpMethod requiredMethod = requiredMethod(exactSuffix);

        CcapiEndpointCatalog.Endpoint endpoint = requiredMethod == null
                ? catalog.findExactPath(exactSuffix).orElse(null)
                : catalog.findExactPath(exactSuffix, requiredMethod).orElse(null);
        if (endpoint != null) {
            return endpoint.url();
        }

        CcapiEndpointCatalog.Endpoint advertised = catalog.findExactPath(exactSuffix)
                .orElse(null);
        if (advertised != null && requiredMethod != null
                && advertised.methodMetadataKnown()) {
            throw new CameraException("CCAPI endpoint " + advertised.path()
                    + " does not advertise " + requiredMethod.name());
        }

        log.debug("CCAPI endpoint '{}' was not advertised; using legacy fallback {}",
                exactSuffix, defaultPath);
        return baseUrl + defaultPath;
    }

    /**
     * Stops live view through the exact shared catalog while reusing the base
     * backend's single raw-JSON POST and bounded Device-busy retry implementation.
     * Delegating to {@link #startLiveView(String)} with {@code off} remains
     * method-aware because {@link #resolveEndpoint(String, String)} is virtual.
     * The final base stop call only resets its lifecycle flag: this specialization
     * never populates the base class's private endpoint map, so it cannot emit a
     * second request.
     */
    @Override
    public void stopLiveView() {
        String url = findEndpoint(LIVE_VIEW_SUFFIX);
        if (url != null && endpointAllows(LIVE_VIEW_SUFFIX, HttpMethod.POST, true)) {
            try {
                super.startLiveView("off");
                log.info("Live view stopped.");
            } catch (CameraException exception) {
                if (exception.getCause() instanceof RestClientException) {
                    log.debug("Failed to stop live view (may already be off): {}",
                            exception.getMessage());
                } else {
                    throw exception;
                }
            }
        }
        super.stopLiveView();
    }

    @Override
    public Map<String, String> getDiscoveredEndpoints() {
        Map<String, String> result = new LinkedHashMap<>();
        for (CcapiEndpointCatalog.Endpoint endpoint : endpointCatalog().endpoints()) {
            result.put(endpoint.advertisedValue(), endpoint.url());
        }
        return Map.copyOf(result);
    }

    @Override
    public Map<String, Set<HttpMethod>> getDiscoveredEndpointMethods() {
        Map<String, Set<HttpMethod>> result = new LinkedHashMap<>();
        for (CcapiEndpointCatalog.Endpoint endpoint : endpointCatalog().endpoints()) {
            if (endpoint.methodMetadataKnown()) {
                result.put(endpoint.advertisedValue(), endpoint.methods());
            }
        }
        return Map.copyOf(result);
    }

    private static HttpMethod requiredMethod(String suffix) {
        String normalized = suffix == null ? "" : suffix.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        if (normalized.endsWith("/deviceinformation")
                || normalized.contains("/devicestatus/")
                || normalized.endsWith("/battery")
                || normalized.endsWith("/batterylist")
                || normalized.endsWith("/storage")
                || normalized.endsWith("/currentstorage")
                || normalized.endsWith("/liveview/flip")
                || normalized.contains("/contents")
                || normalized.endsWith("/event/polling")) {
            return HttpMethod.GET;
        }
        if (normalized.endsWith("/shooting/liveview")
                || normalized.equals("/liveview")
                || normalized.contains("/shooting/control/")
                || normalized.endsWith("/shutterbutton")
                || normalized.endsWith("/shutterbutton/manual")
                || normalized.endsWith("/drivefocus")
                || normalized.endsWith("/af")) {
            return HttpMethod.POST;
        }
        return null;
    }

    private static String suffixFromDefaultPath(String defaultPath, String requestedSuffix) {
        if (defaultPath != null) {
            int versionRoot = defaultPath.indexOf(VERSION_ROOT_PREFIX);
            if (versionRoot >= 0) {
                int operationPath = defaultPath.indexOf('/',
                        versionRoot + VERSION_ROOT_PREFIX.length());
                if (operationPath >= 0) {
                    return defaultPath.substring(operationPath);
                }
            }
        }
        return requestedSuffix;
    }

    private ReadResult<BatteryInfo> readBattery() {
        CcapiEndpointCatalog catalog = endpointCatalog();
        String batteryListUrl = findGetEndpoint(catalog, BATTERY_LIST_SUFFIX);
        if (batteryListUrl != null) {
            try {
                BatteryListResponse response = restTemplate.getForObject(
                        batteryListUrl, BatteryListResponse.class);
                return new ReadResult<>(worstBattery(response), true);
            } catch (RestClientException exception) {
                log.debug("CCAPI battery-list status unavailable: {}", exception.getMessage());
                return new ReadResult<>(BatteryInfo.unknown(), false);
            }
        }

        String batteryUrl = findGetEndpoint(catalog, BATTERY_SUFFIX);
        if (batteryUrl == null) {
            return new ReadResult<>(BatteryInfo.unknown(), false);
        }
        try {
            BatteryEntry response = restTemplate.getForObject(batteryUrl, BatteryEntry.class);
            return new ReadResult<>(toBatteryInfo(response), true);
        } catch (RestClientException exception) {
            log.debug("CCAPI battery status unavailable: {}", exception.getMessage());
            return new ReadResult<>(BatteryInfo.unknown(), false);
        }
    }

    private ReadResult<StorageInfo> readStorage() {
        CcapiEndpointCatalog catalog = endpointCatalog();
        String storageUrl = findGetEndpoint(catalog, STORAGE_SUFFIX);
        if (storageUrl == null) {
            return new ReadResult<>(StorageInfo.empty(), false);
        }
        try {
            StorageListResponse response = restTemplate.getForObject(
                    storageUrl, StorageListResponse.class);
            return new ReadResult<>(toStorageInfo(response), true);
        } catch (RestClientException exception) {
            log.debug("CCAPI storage status unavailable: {}", exception.getMessage());
            return new ReadResult<>(StorageInfo.empty(), false);
        }
    }

    private static String findGetEndpoint(CcapiEndpointCatalog catalog, String suffix) {
        return catalog.findExactPath(suffix, HttpMethod.GET)
                .map(CcapiEndpointCatalog.Endpoint::url)
                .orElse(null);
    }

    private CcapiEndpointCatalog endpointCatalog() {
        CcapiEndpointCatalog current = endpointCatalog;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = endpointCatalog;
            if (current != null) {
                return current;
            }
            CcapiEndpointCatalog discovered = CcapiEndpointCatalog.discover(baseUrl, restTemplate);
            if (!discovered.endpoints().isEmpty()) {
                endpointCatalog = discovered;
            }
            return discovered;
        }
    }

    static StorageInfo toStorageInfo(StorageListResponse response) {
        if (response == null || response.storagelist() == null
                || response.storagelist().isEmpty()) {
            return StorageInfo.empty();
        }
        List<StorageInfo.Volume> volumes = new ArrayList<>();
        int index = 0;
        for (StorageEntry entry : response.storagelist()) {
            if (entry == null) {
                continue;
            }
            index++;
            String location = firstNonBlank(entry.path(), entry.url());
            String name = firstNonBlank(entry.name(), lastPathSegment(location));
            if (name == null) {
                name = "storage-" + index;
            }
            volumes.add(new StorageInfo.Volume(
                    name,
                    location,
                    knownLong(entry.maxsize()),
                    knownLong(entry.spacesize()),
                    knownCount(entry.contentsnumber()),
                    normalizedOrUnknown(entry.accesscapability())
            ));
        }
        return volumes.isEmpty() ? StorageInfo.empty() : new StorageInfo(volumes);
    }

    private static BatteryInfo worstBattery(BatteryListResponse response) {
        if (response == null || response.batterylist() == null
                || response.batterylist().isEmpty()) {
            return BatteryInfo.unknown();
        }
        BatteryInfo worstKnown = null;
        BatteryInfo firstUnknown = null;
        for (BatteryEntry entry : response.batterylist()) {
            BatteryInfo candidate = toBatteryInfo(entry);
            if (candidate.percent() >= 0) {
                if (worstKnown == null || candidate.percent() < worstKnown.percent()) {
                    worstKnown = candidate;
                }
            } else if (firstUnknown == null) {
                firstUnknown = candidate;
            }
        }
        return worstKnown != null ? worstKnown
                : firstUnknown != null ? firstUnknown : BatteryInfo.unknown();
    }

    private static BatteryInfo toBatteryInfo(BatteryEntry entry) {
        if (entry == null) {
            return BatteryInfo.unknown();
        }
        return new BatteryInfo(
                normalizedOrUnknown(entry.level()),
                normalizedOrUnknown(entry.kind()),
                normalizedOrUnknown(entry.quality()),
                strictBatteryPercent(entry.level())
        );
    }

    static int strictBatteryPercent(String level) {
        if (level == null || level.isBlank()) {
            return -1;
        }
        String normalized = level.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "full" -> 100;
            case "high" -> 80;
            case "half", "middle" -> 50;
            case "quarter" -> 25;
            case "low" -> 20;
            case "empty" -> 0;
            default -> parseNumericPercent(normalized);
        };
    }

    private static int parseNumericPercent(String value) {
        try {
            int numeric = Integer.parseInt(value);
            return numeric >= 0 && numeric <= 100 ? numeric : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static long knownLong(Long value) {
        return value == null || value < 0L ? -1L : value;
    }

    private static int knownCount(Long value) {
        if (value == null || value < 0L) {
            return -1;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }

    private static String normalizedOrUnknown(String value) {
        return value == null || value.isBlank()
                ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String lastPathSegment(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String path;
        try {
            path = URI.create(location).getPath();
        } catch (IllegalArgumentException exception) {
            path = location;
        }
        if (path == null) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) {
            return null;
        }
        return path.substring(slash + 1);
    }

    private static String normalizeBaseUrl(String value) {
        Objects.requireNonNull(value, "baseUrl");
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record ReadResult<T>(T value, boolean reachable) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StorageListResponse(
            @JsonProperty("storagelist") List<StorageEntry> storagelist
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StorageEntry(
            @JsonProperty("name") String name,
            @JsonProperty("url") String url,
            @JsonProperty("path") String path,
            @JsonProperty("accesscapability") String accesscapability,
            @JsonProperty("maxsize") Long maxsize,
            @JsonProperty("spacesize") Long spacesize,
            @JsonProperty("contentsnumber") Long contentsnumber
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatteryListResponse(
            @JsonProperty("batterylist") List<BatteryEntry> batterylist
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatteryEntry(
            @JsonProperty("position") String position,
            @JsonProperty("name") String name,
            @JsonProperty("kind") String kind,
            @JsonProperty("level") String level,
            @JsonProperty("quality") String quality
    ) {
    }
}
