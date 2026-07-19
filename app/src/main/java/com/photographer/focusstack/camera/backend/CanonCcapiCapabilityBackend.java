package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraCapabilityStatusProvider;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraLensStatusProvider;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CameraStatusProvider;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.NativeFocusBracketingCapabilities;
import com.photographer.focusstack.common.camera.NativeFocusBracketingProvider;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingSession;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.common.capability.Capability;
import com.photographer.focusstack.common.capability.CapabilitySet;
import com.photographer.focusstack.common.capability.CapabilityStatus;
import com.photographer.focusstack.common.capability.CapabilityStatusSnapshot;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.LensInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.photographer.focusstack.common.capability.CapabilityStatus.TemporaryCondition.ALREADY_STARTED;
import static com.photographer.focusstack.common.capability.CapabilityStatus.TemporaryCondition.DEVICE_BUSY;
import static com.photographer.focusstack.common.capability.CapabilityStatus.TemporaryCondition.LIVE_VIEW_NOT_STARTED;
import static com.photographer.focusstack.common.capability.CapabilityStatus.TemporaryCondition.MEDIA_WRITE_UNAVAILABLE;
import static com.photographer.focusstack.common.capability.CapabilityStatus.TemporaryCondition.MODE_NOT_SUPPORTED;
import static com.photographer.focusstack.common.capability.CapabilityStatus.TemporaryCondition.OPERATION_NOT_STARTED;
import static com.photographer.focusstack.common.capability.CapabilityStatus.TemporaryCondition.OUT_OF_FOCUS;
import static com.photographer.focusstack.common.capability.CapabilityStatus.TemporaryCondition.PREPARING;
import static com.photographer.focusstack.common.capability.CapabilityStatus.TemporaryCondition.SHOOTING_OR_RECORDING;
import static com.photographer.focusstack.common.capability.CapabilityStatus.TemporaryCondition.UNKNOWN;

/**
 * Production Canon backend facade that adds evidence-rich, vendor-neutral status
 * without widening the legacy camera or native-bracketing interfaces.
 */
public final class CanonCcapiCapabilityBackend
        implements CameraBackend,
        CameraStatusProvider,
        CameraLensStatusProvider,
        CameraCapabilityStatusProvider,
        NativeFocusBracketingProvider {

    private static final String DEVICE_INFO = "/deviceinformation";
    private static final String BATTERY = "/devicestatus/battery";
    private static final String BATTERY_LIST = "/devicestatus/batterylist";
    private static final String STORAGE = "/devicestatus/storage";
    private static final String LENS = "/devicestatus/lens";

    private static final List<CapabilitySpec> SPECS = List.of(
            CapabilitySpec.all(Capability.DEVICE_INFO, CapabilitySet.Access.READ_ONLY, true,
                    requirement(DEVICE_INFO, HttpMethod.GET)),
            CapabilitySpec.any(Capability.BATTERY_INFO, CapabilitySet.Access.READ_ONLY, true,
                    requirement(BATTERY_LIST, HttpMethod.GET),
                    requirement(BATTERY, HttpMethod.GET)),
            CapabilitySpec.all(Capability.STORAGE_INFO, CapabilitySet.Access.READ_ONLY, true,
                    requirement(STORAGE, HttpMethod.GET)),
            CapabilitySpec.all(Capability.LENS_INFO, CapabilitySet.Access.READ_ONLY, true,
                    requirement(LENS, HttpMethod.GET)),
            CapabilitySpec.any(Capability.EVENT_MONITORING, CapabilitySet.Access.READ_ONLY, false,
                    requirement("/event/monitoring", HttpMethod.GET),
                    requirement("/event/polling", HttpMethod.GET)),
            CapabilitySpec.all(Capability.LIVE_VIEW, CapabilitySet.Access.READ_ONLY, true,
                    requirement("/shooting/liveview", HttpMethod.POST),
                    requirement("/shooting/liveview/flip", HttpMethod.GET)),
            CapabilitySpec.any(Capability.CAPTURE_STILL, CapabilitySet.Access.READ_WRITE, true,
                    requirement("/shooting/control/shutterbutton/manual", HttpMethod.POST),
                    requirement("/shooting/control/shutterbutton", HttpMethod.POST)),
            CapabilitySpec.all(Capability.VIDEO_RECORD, CapabilitySet.Access.READ_WRITE, false,
                    requirement("/shooting/control/recbutton", HttpMethod.POST)),
            CapabilitySpec.all(Capability.FOCUS_DRIVE, CapabilitySet.Access.READ_WRITE, true,
                    requirement("/shooting/control/drivefocus", HttpMethod.POST)),
            CapabilitySpec.all(Capability.ZOOM_CONTROL, CapabilitySet.Access.READ_WRITE, false,
                    requirement("/shooting/control/zoom", HttpMethod.POST)),
            CapabilitySpec.all(Capability.SHOOTING_MODE_READ, CapabilitySet.Access.READ_ONLY, false,
                    requirement("/shooting/settings/shootingmode", HttpMethod.GET)),
            CapabilitySpec.all(Capability.SHOOTING_MODE_WRITE, CapabilitySet.Access.READ_WRITE, false,
                    requirement("/shooting/settings/shootingmode", HttpMethod.PUT)),
            CapabilitySpec.any(Capability.EXPOSURE_READ, CapabilitySet.Access.READ_ONLY, false,
                    requirement("/shooting/settings/av", HttpMethod.GET),
                    requirement("/shooting/settings/tv", HttpMethod.GET),
                    requirement("/shooting/settings/iso", HttpMethod.GET),
                    requirement("/shooting/settings/exposure", HttpMethod.GET)),
            CapabilitySpec.any(Capability.EXPOSURE_WRITE, CapabilitySet.Access.READ_WRITE, false,
                    requirement("/shooting/settings/av", HttpMethod.PUT),
                    requirement("/shooting/settings/tv", HttpMethod.PUT),
                    requirement("/shooting/settings/iso", HttpMethod.PUT),
                    requirement("/shooting/settings/exposure", HttpMethod.PUT))
    );

    private final CanonCcapiNativeFocusBracketingBackend delegate;
    private final RestTemplate restTemplate;
    private final Map<Capability, CapabilityStatus.TemporaryCondition> temporaryConditions =
            new ConcurrentHashMap<>();
    private volatile CameraInfo lastKnownInfo;

    public CanonCcapiCapabilityBackend(String baseUrl, RestTemplate restTemplate) {
        this.delegate = new CanonCcapiNativeFocusBracketingBackend(baseUrl, restTemplate);
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    }

    @Override
    public CameraInfo getInfo() {
        CameraInfo info = guarded(Capability.DEVICE_INFO, delegate::getInfo);
        lastKnownInfo = info;
        return info;
    }

    @Override
    public CameraState getState() {
        return delegate.getState();
    }

    @Override
    public LiveViewFrame getLiveView() {
        return guarded(Capability.LIVE_VIEW, delegate::getLiveView);
    }

    @Override
    public CaptureResult capture() {
        return guarded(Capability.CAPTURE_STILL, delegate::capture);
    }

    @Override
    public void setFocus(FocusCommand command) {
        guarded(Capability.FOCUS_DRIVE, () -> {
            delegate.setFocus(command);
            return null;
        });
    }

    @Override
    public void stopLiveView() {
        guarded(Capability.LIVE_VIEW, () -> {
            delegate.stopLiveView();
            return null;
        });
    }

    @Override
    public boolean supports(Operation operation) {
        return delegate.supports(operation);
    }

    @Override
    public BatteryInfo getBatteryInfo() {
        return delegate.getBatteryInfo();
    }

    @Override
    public StorageInfo getStorageInfo() {
        return delegate.getStorageInfo();
    }

    @Override
    public LensInfo getLensInfo() {
        CatalogEvidence catalog = catalogEvidence();
        Advertisement lens = catalog.find(LENS, HttpMethod.GET);
        if (lens == null) {
            temporaryConditions.remove(Capability.LENS_INFO);
            return LensInfo.none();
        }
        return guarded(Capability.LENS_INFO, () -> {
            LensResponse response = restTemplate.getForObject(lens.url(), LensResponse.class);
            if (response == null) {
                return LensInfo.none();
            }
            boolean attached = response.mount();
            String name = attached && response.name() != null && !response.name().isBlank()
                    ? response.name().trim()
                    : null;
            return new LensInfo(name, -1, -1, -1, null, attached);
        });
    }

    @Override
    public CapabilityStatusSnapshot getCapabilityStatus() {
        CatalogEvidence catalog = catalogEvidence();
        CameraInfo info = lastKnownInfo;
        if (catalog.advertises(DEVICE_INFO, HttpMethod.GET)) {
            info = getInfo();
        }

        EnumMap<Capability, CapabilityStatus> statuses = new EnumMap<>(Capability.class);
        if (!catalog.authoritative()) {
            for (Capability capability : Capability.values()) {
                statuses.put(capability, CapabilityStatus.unknown(capability));
            }
        } else {
            for (CapabilitySpec spec : SPECS) {
                boolean advertised = spec.advertised(catalog);
                if (!advertised) {
                    statuses.put(spec.capability(),
                            CapabilityStatus.unsupported(spec.capability()));
                    continue;
                }
                if (!spec.implemented()) {
                    statuses.put(spec.capability(),
                            CapabilityStatus.advertisedNotImplemented(spec.capability()));
                    continue;
                }
                CapabilityStatus.TemporaryCondition temporary =
                        temporaryConditions.get(spec.capability());
                statuses.put(spec.capability(), temporary == null
                        ? CapabilityStatus.available(spec.capability(), spec.access())
                        : CapabilityStatus.temporarilyUnavailable(
                                spec.capability(), spec.access(), temporary));
            }
        }

        return new CapabilityStatusSnapshot(
                info == null ? "unknown" : info.model(),
                info == null ? "unknown" : info.firmwareVersion(),
                Instant.now(),
                statuses,
                catalog.sanitizedEndpoints());
    }

    @Override
    public NativeFocusBracketingCapabilities getNativeFocusBracketingCapabilities() {
        return delegate.getNativeFocusBracketingCapabilities();
    }

    @Override
    public NativeFocusBracketingSession prepareNativeFocusBracketing(
            NativeFocusBracketingRequest request) {
        return delegate.prepareNativeFocusBracketing(request);
    }

    private <T> T guarded(Capability capability, Supplier<T> operation) {
        try {
            T result = operation.get();
            temporaryConditions.remove(capability);
            return result;
        } catch (RuntimeException failure) {
            CapabilityStatus.TemporaryCondition temporary = temporaryCondition(failure);
            if (temporary == null) {
                temporaryConditions.remove(capability);
            } else {
                temporaryConditions.put(capability, temporary);
            }
            throw failure;
        }
    }

    private static CapabilityStatus.TemporaryCondition temporaryCondition(Throwable failure) {
        CameraOperationException operation = operationFailure(failure);
        if (operation == null
                || operation.category() != CameraOperationException.Category.TEMPORARY_STATE) {
            return null;
        }
        return switch (operation.reason()) {
            case DEVICE_BUSY -> DEVICE_BUSY;
            case DURING_SHOOTING_OR_RECORDING -> SHOOTING_OR_RECORDING;
            case TAKEN_IN_PREPARATION -> PREPARING;
            case MODE_NOT_SUPPORTED -> MODE_NOT_SUPPORTED;
            case LIVE_VIEW_NOT_STARTED -> LIVE_VIEW_NOT_STARTED;
            case ALREADY_STARTED -> ALREADY_STARTED;
            case OUT_OF_FOCUS -> OUT_OF_FOCUS;
            case CANNOT_WRITE_TO_CARD -> MEDIA_WRITE_UNAVAILABLE;
            case NOT_STARTED -> OPERATION_NOT_STARTED;
            case SERVICE_UNAVAILABLE -> UNKNOWN;
            default -> null;
        };
    }

    private static CameraOperationException operationFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CameraOperationException operation) {
                return operation;
            }
            if (current instanceof CameraException cameraException) {
                CameraOperationException nested = cameraException.operationFailure().orElse(null);
                if (nested != null) {
                    return nested;
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private CatalogEvidence catalogEvidence() {
        Map<String, String> urls = delegate.getDiscoveredEndpoints();
        Map<String, Set<HttpMethod>> methods = delegate.getDiscoveredEndpointMethods();
        List<Advertisement> advertisements = new ArrayList<>();
        for (Map.Entry<String, String> entry : urls.entrySet()) {
            String advertisedValue = entry.getKey();
            String path = normalizedPath(advertisedValue);
            if (path == null) {
                path = normalizedPath(entry.getValue());
            }
            if (path == null) {
                continue;
            }
            boolean methodMetadataKnown = methods.containsKey(advertisedValue);
            Set<HttpMethod> advertisedMethods = methodMetadataKnown
                    ? Set.copyOf(methods.getOrDefault(advertisedValue, Set.of()))
                    : Set.of();
            advertisements.add(new Advertisement(
                    version(path), path, entry.getValue(), advertisedMethods,
                    methodMetadataKnown));
        }
        advertisements.sort(Comparator
                .comparing(Advertisement::version)
                .thenComparing(Advertisement::path));
        return new CatalogEvidence(advertisements);
    }

    private static String normalizedPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            URI uri = URI.create(trimmed);
            String path = uri.getPath();
            if (path != null && !path.isBlank()) {
                return normalizePath(path);
            }
        } catch (IllegalArgumentException ignored) {
            // Treat a non-URI value as a path below.
        }
        return trimmed.startsWith("/") ? normalizePath(trimmed) : null;
    }

    private static String normalizePath(String path) {
        String normalized = path.trim();
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String version(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int start = lower.indexOf("/ccapi/ver");
        if (start < 0) {
            return "unknown";
        }
        int valueStart = start + "/ccapi/ver".length();
        int slash = lower.indexOf('/', valueStart);
        return slash < 0 ? lower.substring(valueStart) : lower.substring(valueStart, slash);
    }

    private static Requirement requirement(String suffix, HttpMethod method) {
        return new Requirement(suffix, method);
    }

    private record Requirement(String suffix, HttpMethod method) {
        private Requirement {
            Objects.requireNonNull(suffix, "suffix");
            Objects.requireNonNull(method, "method");
            if (!suffix.startsWith("/")) {
                throw new IllegalArgumentException("Capability suffix must start with '/'");
            }
        }
    }

    private enum MatchMode {
        ANY,
        ALL
    }

    private record CapabilitySpec(
            Capability capability,
            CapabilitySet.Access access,
            boolean implemented,
            MatchMode matchMode,
            List<Requirement> requirements) {

        private CapabilitySpec {
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(matchMode, "matchMode");
            requirements = List.copyOf(requirements);
            if (requirements.isEmpty()) {
                throw new IllegalArgumentException("Capability requirements must not be empty");
            }
        }

        private static CapabilitySpec any(
                Capability capability,
                CapabilitySet.Access access,
                boolean implemented,
                Requirement... requirements) {
            return new CapabilitySpec(capability, access, implemented,
                    MatchMode.ANY, List.of(requirements));
        }

        private static CapabilitySpec all(
                Capability capability,
                CapabilitySet.Access access,
                boolean implemented,
                Requirement... requirements) {
            return new CapabilitySpec(capability, access, implemented,
                    MatchMode.ALL, List.of(requirements));
        }

        private boolean advertised(CatalogEvidence catalog) {
            return matchMode == MatchMode.ALL
                    ? requirements.stream().allMatch(catalog::advertises)
                    : requirements.stream().anyMatch(catalog::advertises);
        }
    }

    private record Advertisement(
            String version,
            String path,
            String url,
            Set<HttpMethod> methods,
            boolean methodMetadataKnown) {

        private Advertisement {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(url, "url");
            methods = Set.copyOf(methods);
        }

        private boolean advertises(String suffix, HttpMethod method) {
            return path.endsWith(suffix)
                    && (!methodMetadataKnown || methods.contains(method));
        }
    }

    private record CatalogEvidence(List<Advertisement> advertisements) {
        private CatalogEvidence {
            advertisements = List.copyOf(advertisements);
        }

        private boolean authoritative() {
            return !advertisements.isEmpty();
        }

        private boolean advertises(Requirement requirement) {
            return advertises(requirement.suffix(), requirement.method());
        }

        private boolean advertises(String suffix, HttpMethod method) {
            return advertisements.stream()
                    .anyMatch(endpoint -> endpoint.advertises(suffix, method));
        }

        private Advertisement find(String suffix, HttpMethod method) {
            return advertisements.stream()
                    .filter(endpoint -> endpoint.advertises(suffix, method))
                    .max(Comparator.comparing(Advertisement::version))
                    .orElse(null);
        }

        private List<CapabilityStatusSnapshot.AdvertisedEndpoint> sanitizedEndpoints() {
            Map<String, CapabilityStatusSnapshot.AdvertisedEndpoint> unique =
                    new LinkedHashMap<>();
            for (Advertisement endpoint : advertisements) {
                Set<String> methodNames = new LinkedHashSet<>();
                endpoint.methods().stream()
                        .map(HttpMethod::name)
                        .sorted()
                        .forEach(methodNames::add);
                CapabilityStatusSnapshot.AdvertisedEndpoint sanitized =
                        new CapabilityStatusSnapshot.AdvertisedEndpoint(
                                endpoint.version(), endpoint.path(), methodNames,
                                endpoint.methodMetadataKnown());
                unique.put(endpoint.version() + " " + endpoint.path(), sanitized);
            }
            return List.copyOf(unique.values());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LensResponse(
            @JsonProperty("mount") boolean mount,
            @JsonProperty("name") String name) {
    }
}
