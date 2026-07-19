package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.common.camera.IntegerSettingRange;
import com.photographer.focusstack.common.camera.NativeFocusBracketingCapabilities;
import com.photographer.focusstack.common.camera.NativeFocusBracketingProvider;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingSession;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canon CCAPI backend extension for camera-owned focus-bracketing settings.
 *
 * <p>This adapter deliberately prepares and restores settings only. It neither
 * triggers the shutter nor assumes how many media events one native sequence
 * produces. Capture observation and ingestion remain a separate strategy layer.</p>
 *
 * <p>A setting is usable only when one exact advertised endpoint declares both
 * {@code GET} and {@code PUT}. Methodless legacy endpoint lists are therefore not
 * sufficient for native bracketing writes.</p>
 */
public final class CanonCcapiNativeFocusBracketingBackend
        extends CanonCcapiStatusBackend
        implements NativeFocusBracketingProvider {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?:^|/)ver(\\d+)(?:/|$)");
    private static final int DEFAULT_DEVICE_BUSY_MAX_RETRIES = 5;
    private static final long DEFAULT_DEVICE_BUSY_INITIAL_BACKOFF_MS = 500L;

    private final RestTemplate restTemplate;
    private final int deviceBusyMaxRetries;
    private final long deviceBusyInitialBackoffMs;

    public CanonCcapiNativeFocusBracketingBackend(String baseUrl, RestTemplate restTemplate) {
        this(baseUrl, restTemplate,
                DEFAULT_DEVICE_BUSY_MAX_RETRIES,
                DEFAULT_DEVICE_BUSY_INITIAL_BACKOFF_MS);
    }

    CanonCcapiNativeFocusBracketingBackend(
            String baseUrl,
            RestTemplate restTemplate,
            int deviceBusyMaxRetries,
            long deviceBusyInitialBackoffMs) {
        super(baseUrl, restTemplate);
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        if (deviceBusyMaxRetries < 0) {
            throw new IllegalArgumentException("deviceBusyMaxRetries must not be negative");
        }
        if (deviceBusyInitialBackoffMs < 0L) {
            throw new IllegalArgumentException("deviceBusyInitialBackoffMs must not be negative");
        }
        this.deviceBusyMaxRetries = deviceBusyMaxRetries;
        this.deviceBusyInitialBackoffMs = deviceBusyInitialBackoffMs;
    }

    @Override
    public NativeFocusBracketingCapabilities getNativeFocusBracketingCapabilities() {
        return readCapabilitySnapshot().capabilities();
    }

    @Override
    public NativeFocusBracketingSession prepareNativeFocusBracketing(
            NativeFocusBracketingRequest request) {
        CapabilitySnapshot snapshot = readCapabilitySnapshot();
        return FocusBracketingSettingsTransaction.prepare(
                new CcapiSettingAccess(snapshot.endpoints()),
                snapshot.capabilities(),
                request);
    }

    private CapabilitySnapshot readCapabilitySnapshot() {
        Map<FocusBracketingSettingsTransaction.Setting, SettingEndpoint> endpoints =
                resolveSettingEndpoints();

        BooleanSettingResponse enabled = readBooleanCapability(
                endpoints.get(FocusBracketingSettingsTransaction.Setting.ENABLED));
        IntegerSettingResponse numberOfShots = readIntegerCapability(
                endpoints.get(FocusBracketingSettingsTransaction.Setting.NUMBER_OF_SHOTS));
        IntegerSettingResponse focusIncrement = readIntegerCapability(
                endpoints.get(FocusBracketingSettingsTransaction.Setting.FOCUS_INCREMENT));
        BooleanSettingResponse exposureSmoothing = readBooleanCapability(
                endpoints.get(FocusBracketingSettingsTransaction.Setting.EXPOSURE_SMOOTHING));
        BooleanSettingResponse depthComposition = readBooleanCapability(
                endpoints.get(FocusBracketingSettingsTransaction.Setting.DEPTH_COMPOSITION));
        BooleanSettingResponse cropDepthComposition = readBooleanCapability(
                endpoints.get(FocusBracketingSettingsTransaction.Setting.CROP_DEPTH_COMPOSITION));

        boolean depthCompositionSupported = supportsBooleanToggle(depthComposition);
        boolean cropDepthCompositionSupported = supportsBooleanToggle(cropDepthComposition);
        if (cropDepthCompositionSupported && !depthCompositionSupported) {
            throw new CameraException(
                    "CCAPI advertised crop depth composition without usable depth composition");
        }

        NativeFocusBracketingCapabilities capabilities =
                new NativeFocusBracketingCapabilities(
                        supportsBooleanToggle(enabled),
                        integerRange(numberOfShots, "number of shots"),
                        integerRange(focusIncrement, "focus increment"),
                        supportsBooleanToggle(exposureSmoothing),
                        depthCompositionSupported,
                        cropDepthCompositionSupported);
        return new CapabilitySnapshot(capabilities, endpoints);
    }

    private Map<FocusBracketingSettingsTransaction.Setting, SettingEndpoint>
    resolveSettingEndpoints() {
        Map<String, String> advertisedEndpoints = getDiscoveredEndpoints();
        Map<String, Set<HttpMethod>> advertisedMethods = getDiscoveredEndpointMethods();
        EnumMap<FocusBracketingSettingsTransaction.Setting, SettingEndpoint> resolved =
                new EnumMap<>(FocusBracketingSettingsTransaction.Setting.class);

        for (Map.Entry<String, String> advertised : advertisedEndpoints.entrySet()) {
            Set<HttpMethod> methods = advertisedMethods.get(advertised.getKey());
            if (methods == null
                    || !methods.contains(HttpMethod.GET)
                    || !methods.contains(HttpMethod.PUT)) {
                continue;
            }
            String path = endpointPath(advertised.getValue());
            String operationPath = operationPath(path);
            if (operationPath == null) {
                continue;
            }
            for (FocusBracketingSettingsTransaction.Setting setting
                    : FocusBracketingSettingsTransaction.Setting.values()) {
                String suffix = suffix(setting);
                if (!operationPath.equals(suffix)) {
                    continue;
                }
                SettingEndpoint candidate = new SettingEndpoint(
                        setting,
                        suffix,
                        advertised.getValue(),
                        versionNumber(path));
                SettingEndpoint current = resolved.get(setting);
                if (current == null || candidate.versionNumber() > current.versionNumber()) {
                    resolved.put(setting, candidate);
                }
            }
        }
        return Map.copyOf(resolved);
    }

    private BooleanSettingResponse readBooleanCapability(SettingEndpoint endpoint) {
        return endpoint == null ? null : get(endpoint, BooleanSettingResponse.class);
    }

    private IntegerSettingResponse readIntegerCapability(SettingEndpoint endpoint) {
        return endpoint == null ? null : get(endpoint, IntegerSettingResponse.class);
    }

    private <T> T get(SettingEndpoint endpoint, Class<T> responseType) {
        try {
            T response = restTemplate.getForObject(endpoint.url(), responseType);
            if (response == null) {
                throw new CameraException("CCAPI returned an empty response for " + endpoint.suffix());
            }
            return response;
        } catch (CameraException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new CameraException(
                    "Could not read native focus-bracketing setting " + endpoint.suffix(),
                    exception);
        }
    }

    private static boolean supportsBooleanToggle(BooleanSettingResponse response) {
        if (response == null || response.ability() == null) {
            return false;
        }
        Set<String> ability = response.ability().stream()
                .filter(Objects::nonNull)
                .map(CanonCcapiNativeFocusBracketingBackend::normalizeToken)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return ability.contains("enable") && ability.contains("disable");
    }

    private static Optional<IntegerSettingRange> integerRange(
            IntegerSettingResponse response,
            String settingName) {
        if (response == null) {
            return Optional.empty();
        }
        RangeAbility ability = response.ability();
        if (ability == null || ability.min() == null
                || ability.max() == null || ability.step() == null) {
            throw new CameraException(
                    "CCAPI returned an incomplete ability range for " + settingName);
        }
        try {
            return Optional.of(new IntegerSettingRange(
                    ability.min(), ability.max(), ability.step()));
        } catch (IllegalArgumentException exception) {
            throw new CameraException(
                    "CCAPI returned an invalid ability range for " + settingName,
                    exception);
        }
    }

    private final class CcapiSettingAccess
            implements FocusBracketingSettingsTransaction.SettingAccess {

        private final Map<FocusBracketingSettingsTransaction.Setting, SettingEndpoint> endpoints;

        private CcapiSettingAccess(
                Map<FocusBracketingSettingsTransaction.Setting, SettingEndpoint> endpoints) {
            this.endpoints = Map.copyOf(endpoints);
        }

        @Override
        public boolean supports(FocusBracketingSettingsTransaction.Setting setting) {
            return endpoints.containsKey(setting);
        }

        @Override
        public Object read(FocusBracketingSettingsTransaction.Setting setting) {
            SettingEndpoint endpoint = requireEndpoint(setting);
            return switch (setting) {
                case ENABLED, EXPOSURE_SMOOTHING, DEPTH_COMPOSITION,
                        CROP_DEPTH_COMPOSITION -> readBooleanValue(endpoint);
                case NUMBER_OF_SHOTS, FOCUS_INCREMENT -> readIntegerValue(endpoint);
            };
        }

        @Override
        public void write(FocusBracketingSettingsTransaction.Setting setting, Object value) {
            SettingEndpoint endpoint = requireEndpoint(setting);
            Object wireValue = switch (setting) {
                case ENABLED, EXPOSURE_SMOOTHING, DEPTH_COMPOSITION,
                        CROP_DEPTH_COMPOSITION -> ((Boolean) value) ? "enable" : "disable";
                case NUMBER_OF_SHOTS, FOCUS_INCREMENT -> (Integer) value;
            };
            putWithDeviceBusyRetry(endpoint, wireValue);
        }

        private SettingEndpoint requireEndpoint(
                FocusBracketingSettingsTransaction.Setting setting) {
            SettingEndpoint endpoint = endpoints.get(setting);
            if (endpoint == null) {
                throw new IllegalStateException(
                        "No exact readable/writable CCAPI endpoint for " + setting);
            }
            return endpoint;
        }
    }

    private boolean readBooleanValue(SettingEndpoint endpoint) {
        BooleanSettingResponse response = get(endpoint, BooleanSettingResponse.class);
        String normalized = normalizeToken(response.value());
        return switch (normalized) {
            case "enable" -> true;
            case "disable" -> false;
            default -> throw new CameraException(
                    "CCAPI returned invalid boolean setting value '"
                            + response.value() + "' for " + endpoint.suffix());
        };
    }

    private int readIntegerValue(SettingEndpoint endpoint) {
        IntegerSettingResponse response = get(endpoint, IntegerSettingResponse.class);
        if (response.value() == null) {
            throw new CameraException(
                    "CCAPI returned no integer setting value for " + endpoint.suffix());
        }
        return response.value();
    }

    private void putWithDeviceBusyRetry(SettingEndpoint endpoint, Object value) {
        long backoffMs = deviceBusyInitialBackoffMs;
        for (int attempt = 0; ; attempt++) {
            try {
                restTemplate.exchange(
                        endpoint.url(),
                        HttpMethod.PUT,
                        ccapiEntity(Map.of("value", value)),
                        String.class);
                return;
            } catch (HttpServerErrorException.ServiceUnavailable exception) {
                String body = exception.getResponseBodyAsString();
                boolean deviceBusy = body != null && body.contains("Device busy");
                if (!deviceBusy || attempt >= deviceBusyMaxRetries) {
                    throw unavailableSetting(endpoint, body, exception);
                }
                sleepBeforeRetry(endpoint, backoffMs, attempt + 1);
                backoffMs = Math.min(backoffMs * 2L, 5_000L);
            } catch (RestClientException exception) {
                throw new CameraException(
                        "Could not update native focus-bracketing setting "
                                + endpoint.suffix(),
                        exception);
            }
        }
    }

    private static CameraException unavailableSetting(
            SettingEndpoint endpoint,
            String responseBody,
            RestClientException cause) {
        String reason;
        if (responseBody != null && responseBody.contains("During shooting or recording")) {
            reason = "during shooting or recording";
        } else if (responseBody != null && responseBody.contains("Mode not supported")) {
            reason = "mode not supported";
        } else if (responseBody != null && responseBody.contains("Device busy")) {
            reason = "device busy after bounded retries";
        } else {
            reason = "temporarily unavailable";
        }
        return new CameraException(
                "Native focus-bracketing setting " + endpoint.suffix() + " is " + reason,
                cause);
    }

    private static void sleepBeforeRetry(
            SettingEndpoint endpoint,
            long backoffMs,
            int attempt) {
        if (backoffMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CameraException(
                    "Interrupted while retrying native focus-bracketing setting "
                            + endpoint.suffix() + " (attempt " + attempt + ")",
                    exception);
        }
    }

    private static String suffix(FocusBracketingSettingsTransaction.Setting setting) {
        return switch (setting) {
            case ENABLED -> "/shooting/settings/focusbracketing";
            case NUMBER_OF_SHOTS -> "/shooting/settings/focusbracketing/numberofshots";
            case FOCUS_INCREMENT -> "/shooting/settings/focusbracketing/focusincrement";
            case EXPOSURE_SMOOTHING -> "/shooting/settings/focusbracketing/exposuresmoothing";
            case DEPTH_COMPOSITION -> "/shooting/settings/focusbracketing/depthcomposite";
            case CROP_DEPTH_COMPOSITION -> "/shooting/settings/focusbracketing/cropdepthcomp";
        };
    }

    private static String endpointPath(String endpoint) {
        try {
            return URI.create(endpoint).getPath();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String operationPath(String path) {
        if (path == null) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(path);
        if (!matcher.find()) {
            return null;
        }
        String operation = path.substring(matcher.end());
        return operation.isEmpty() ? "/" : "/" + operation;
    }

    private static int versionNumber(String path) {
        Matcher matcher = VERSION_PATTERN.matcher(path);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record CapabilitySnapshot(
            NativeFocusBracketingCapabilities capabilities,
            Map<FocusBracketingSettingsTransaction.Setting, SettingEndpoint> endpoints) {
    }

    private record SettingEndpoint(
            FocusBracketingSettingsTransaction.Setting setting,
            String suffix,
            String url,
            int versionNumber) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BooleanSettingResponse(
            @JsonProperty("value") String value,
            @JsonProperty("ability") List<String> ability) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IntegerSettingResponse(
            @JsonProperty("value") Integer value,
            @JsonProperty("ability") RangeAbility ability) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RangeAbility(
            @JsonProperty("min") Integer min,
            @JsonProperty("max") Integer max,
            @JsonProperty("step") Integer step) {
    }
}
