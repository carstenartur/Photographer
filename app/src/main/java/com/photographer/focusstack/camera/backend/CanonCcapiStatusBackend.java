package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.photographer.focusstack.common.camera.CameraState;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Canon CCAPI backend with specification-correct rich status mapping.
 *
 * <p>The original backend exposes only the aggregate {@link CameraState} contract.
 * This specialization additionally implements the transport-neutral
 * {@link #getBatteryInfo()} and {@link #getStorageInfo()} methods and derives the
 * legacy state from those snapshots.</p>
 *
 * <p>Endpoint lookup uses {@link CcapiEndpointCatalog}, retains every advertised
 * API version and matches status paths exactly. In particular,
 * {@code /devicestatus/currentstorage} can never be mistaken for the documented
 * {@code /devicestatus/storage} capacity response.</p>
 */
public class CanonCcapiStatusBackend extends CanonCcapiBackend {

    private static final Logger log = LoggerFactory.getLogger(CanonCcapiStatusBackend.class);
    private static final String STORAGE_SUFFIX = "/devicestatus/storage";
    private static final String BATTERY_SUFFIX = "/devicestatus/battery";
    private static final String BATTERY_LIST_SUFFIX = "/devicestatus/batterylist";

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

    private ReadResult<BatteryInfo> readBattery() {
        String batteryListUrl = findGetEndpoint(BATTERY_LIST_SUFFIX);
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

        String batteryUrl = findGetEndpoint(BATTERY_SUFFIX);
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
        String storageUrl = findGetEndpoint(STORAGE_SUFFIX);
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

    private String findGetEndpoint(String suffix) {
        return endpointCatalog().findExactPath(suffix, HttpMethod.GET)
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
            if (current == null) {
                current = CcapiEndpointCatalog.discover(baseUrl, restTemplate);
                endpointCatalog = current;
            }
            return current;
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
