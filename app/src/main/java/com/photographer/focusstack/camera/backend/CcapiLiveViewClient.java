package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ALREADY_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CANCELLED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.DEVICE_BUSY;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.DURING_SHOOTING_OR_RECORDING;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ENDPOINT_NOT_FOUND;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.LIVE_VIEW_NOT_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.METHOD_NOT_ALLOWED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.MODE_NOT_SUPPORTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.NOT_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.SERVICE_UNAVAILABLE;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TAKEN_IN_PREPARATION;

/**
 * Capability-driven Canon CCAPI live-view lifecycle for the production backend.
 *
 * <p>The client maps every exact advertised Canon transport to a vendor-neutral mode,
 * but currently implements frame retrieval only for {@link Mode#SINGLE_FRAME}. An
 * advertised endpoint with incompatible method metadata is never called, and an
 * unimplemented stream mode is never reported as a usable frame source.</p>
 */
final class CcapiLiveViewClient {

    private static final String CONTROL_SUFFIX = "/shooting/liveview";
    private static final Pattern VERSION = Pattern.compile("(?:^|/)ver(\\d+)(?:/|$)");
    private static final Duration DEFAULT_START_DELAY = Duration.ofMillis(500);
    private static final byte[] STOP_BODY =
            "{\"liveviewsize\":\"off\",\"cameradisplay\":\"on\"}"
                    .getBytes(StandardCharsets.UTF_8);

    enum Mode {
        SINGLE_FRAME(
                "single-frame",
                "/shooting/liveview/flip",
                Set.of(HttpMethod.GET),
                true),
        CURSOR_STREAM(
                "cursor-stream",
                "/shooting/liveview/scroll",
                Set.of(HttpMethod.GET, HttpMethod.DELETE),
                false),
        MULTIPART_STREAM(
                "multipart-stream",
                "/shooting/liveview/multipart",
                Set.of(HttpMethod.GET, HttpMethod.DELETE),
                false),
        RTP_STREAM(
                "rtp-stream",
                "/shooting/liveview/rtp",
                Set.of(HttpMethod.POST),
                false);

        private final String id;
        private final String suffix;
        private final Set<HttpMethod> requiredMethods;
        private final boolean frameRetrievalImplemented;

        Mode(
                String id,
                String suffix,
                Set<HttpMethod> requiredMethods,
                boolean frameRetrievalImplemented) {
            this.id = id;
            this.suffix = suffix;
            this.requiredMethods = Set.copyOf(requiredMethods);
            this.frameRetrievalImplemented = frameRetrievalImplemented;
        }

        String id() {
            return id;
        }

        String suffix() {
            return suffix;
        }

        Set<HttpMethod> requiredMethods() {
            return requiredMethods;
        }

        boolean frameRetrievalImplemented() {
            return frameRetrievalImplemented;
        }
    }

    enum State {
        STOPPED,
        STARTING,
        STARTED,
        STOPPING
    }

    record AdvertisedMode(
            Mode mode,
            String path,
            Set<HttpMethod> advertisedMethods,
            boolean methodMetadataKnown,
            boolean methodCompatible,
            boolean frameRetrievalImplemented) {

        AdvertisedMode {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(path, "path");
            advertisedMethods = advertisedMethods == null
                    ? Set.of()
                    : Set.copyOf(advertisedMethods);
        }
    }

    @FunctionalInterface
    interface Poster {
        void post(String url, Object body);
    }

    @FunctionalInterface
    interface Waiter {
        void await(Duration duration) throws InterruptedException;
    }

    private record Endpoint(
            String advertisedValue,
            String path,
            String url,
            Set<HttpMethod> methods,
            boolean methodMetadataKnown,
            int versionNumber) {

        private Endpoint {
            Objects.requireNonNull(advertisedValue, "advertisedValue");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(url, "url");
            methods = methods == null ? Set.of() : Set.copyOf(methods);
        }

        boolean supports(Set<HttpMethod> required) {
            return !methodMetadataKnown || methods.containsAll(required);
        }
    }

    private final String profileId;
    private final RestTemplate restTemplate;
    private final Supplier<Map<String, String>> endpointSupplier;
    private final Supplier<Map<String, Set<HttpMethod>>> methodSupplier;
    private final Poster poster;
    private final Duration startDelay;
    private final Waiter waiter;
    private final Object lifecycleMonitor = new Object();

    private State state = State.STOPPED;
    private String activeSize;

    CcapiLiveViewClient(
            String profileId,
            RestTemplate restTemplate,
            Supplier<Map<String, String>> endpointSupplier,
            Supplier<Map<String, Set<HttpMethod>>> methodSupplier,
            Poster poster) {
        this(
                profileId,
                restTemplate,
                endpointSupplier,
                methodSupplier,
                poster,
                DEFAULT_START_DELAY,
                Thread::sleep);
    }

    CcapiLiveViewClient(
            String profileId,
            RestTemplate restTemplate,
            Supplier<Map<String, String>> endpointSupplier,
            Supplier<Map<String, Set<HttpMethod>>> methodSupplier,
            Poster poster,
            Duration startDelay,
            Waiter waiter) {
        this.profileId = requireProfileId(profileId);
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.endpointSupplier = Objects.requireNonNull(endpointSupplier, "endpointSupplier");
        this.methodSupplier = Objects.requireNonNull(methodSupplier, "methodSupplier");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.startDelay = requireNonNegative(startDelay, "startDelay");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
    }

    LiveViewFrame getFrame() {
        Endpoint frameEndpoint = requireFrameEndpoint();
        start("medium");
        try {
            return readFrame(frameEndpoint);
        } catch (RuntimeException failure) {
            if (reason(failure) != LIVE_VIEW_NOT_STARTED) {
                throw liveViewFailure("Could not read a live-view frame", failure);
            }

            markStopped();
            start("medium");
            try {
                return readFrame(frameEndpoint);
            } catch (RuntimeException retryFailure) {
                throw liveViewFailure(
                        "Could not read a live-view frame after one corrective restart",
                        retryFailure);
            }
        }
    }

    void start(String requestedSize) {
        String size = normalizeSize(requestedSize);
        if ("off".equals(size)) {
            stop();
            return;
        }

        synchronized (lifecycleMonitor) {
            if (state == State.STARTED && size.equals(activeSize)) {
                return;
            }

            Endpoint control = requireEndpoint(CONTROL_SUFFIX, Set.of(HttpMethod.POST));
            State previousState = state;
            String previousSize = activeSize;
            state = State.STARTING;
            try {
                poster.post(control.url(), startBody(size));
                awaitStartDelay();
                state = State.STARTED;
                activeSize = size;
            } catch (RuntimeException failure) {
                if (reason(failure) == ALREADY_STARTED) {
                    state = State.STARTED;
                    activeSize = previousState == State.STARTED ? previousSize : size;
                    return;
                }
                state = previousState == State.STARTED ? State.STARTED : State.STOPPED;
                activeSize = previousSize;
                throw liveViewFailure("Could not start live view", failure);
            }
        }
    }

    void stop() {
        synchronized (lifecycleMonitor) {
            if (state == State.STOPPED) {
                return;
            }

            Endpoint control = requireEndpoint(CONTROL_SUFFIX, Set.of(HttpMethod.POST));
            State previousState = state;
            String previousSize = activeSize;
            state = State.STOPPING;
            try {
                poster.post(control.url(), STOP_BODY.clone());
                state = State.STOPPED;
                activeSize = null;
            } catch (RuntimeException failure) {
                if (reason(failure) == NOT_STARTED) {
                    state = State.STOPPED;
                    activeSize = null;
                    return;
                }
                state = previousState == State.STARTED ? State.STARTED : State.STOPPED;
                activeSize = previousSize;
                throw liveViewFailure("Could not stop live view", failure);
            }
        }
    }

    void markStopped() {
        synchronized (lifecycleMonitor) {
            state = State.STOPPED;
            activeSize = null;
        }
    }

    boolean supportsFrameRetrieval() {
        return selectFrameEndpoint().isPresent();
    }

    List<AdvertisedMode> advertisedModes() {
        Map<Mode, AdvertisedMode> modes = new EnumMap<>(Mode.class);
        for (Mode mode : Mode.values()) {
            Optional<Endpoint> compatible = highestCompatible(
                    mode.suffix(), mode.requiredMethods());
            Optional<Endpoint> selected = compatible.isPresent()
                    ? compatible
                    : highestExact(mode.suffix());
            selected.ifPresent(endpoint -> modes.put(mode, new AdvertisedMode(
                    mode,
                    endpoint.path(),
                    endpoint.methods(),
                    endpoint.methodMetadataKnown(),
                    compatible.isPresent(),
                    mode.frameRetrievalImplemented())));
        }
        return List.copyOf(modes.values());
    }

    State state() {
        synchronized (lifecycleMonitor) {
            return state;
        }
    }

    private Optional<Endpoint> selectFrameEndpoint() {
        for (Mode mode : Mode.values()) {
            if (!mode.frameRetrievalImplemented()) {
                continue;
            }
            Optional<Endpoint> selected = highestCompatible(mode.suffix(), mode.requiredMethods());
            if (selected.isPresent()) {
                return selected;
            }
        }
        return Optional.empty();
    }

    private Endpoint requireFrameEndpoint() {
        Optional<Endpoint> selected = selectFrameEndpoint();
        if (selected.isPresent()) {
            return selected.orElseThrow();
        }

        Optional<Endpoint> advertisedSingleFrame = highestExact(Mode.SINGLE_FRAME.suffix());
        if (advertisedSingleFrame.isPresent()
                && advertisedSingleFrame.orElseThrow().methodMetadataKnown()) {
            throw operation(METHOD_NOT_ALLOWED);
        }
        throw operation(ENDPOINT_NOT_FOUND);
    }

    private Endpoint requireEndpoint(String suffix, Set<HttpMethod> requiredMethods) {
        Optional<Endpoint> selected = highestCompatible(suffix, requiredMethods);
        if (selected.isPresent()) {
            return selected.orElseThrow();
        }

        Optional<Endpoint> advertised = highestExact(suffix);
        if (advertised.isPresent() && advertised.orElseThrow().methodMetadataKnown()) {
            throw operation(METHOD_NOT_ALLOWED);
        }
        throw operation(ENDPOINT_NOT_FOUND);
    }

    private Optional<Endpoint> highestCompatible(
            String suffix,
            Set<HttpMethod> requiredMethods) {
        return endpoints().stream()
                .filter(endpoint -> endpoint.path().endsWith(normalizeSuffix(suffix)))
                .filter(endpoint -> endpoint.supports(requiredMethods))
                .max(endpointOrder());
    }

    private Optional<Endpoint> highestExact(String suffix) {
        return endpoints().stream()
                .filter(endpoint -> endpoint.path().endsWith(normalizeSuffix(suffix)))
                .max(endpointOrder());
    }

    private List<Endpoint> endpoints() {
        Map<String, String> advertised = Optional.ofNullable(endpointSupplier.get())
                .orElse(Map.of());
        Map<String, Set<HttpMethod>> methods = Optional.ofNullable(methodSupplier.get())
                .orElse(Map.of());
        List<Endpoint> result = new ArrayList<>(advertised.size());
        advertised.forEach((advertisedValue, url) -> {
            if (advertisedValue == null || url == null) {
                return;
            }
            Set<HttpMethod> advertisedMethods = methods.get(advertisedValue);
            String path = endpointPath(url, advertisedValue);
            result.add(new Endpoint(
                    advertisedValue,
                    path,
                    url,
                    advertisedMethods,
                    advertisedMethods != null,
                    versionNumber(path)));
        });
        return List.copyOf(result);
    }

    private LiveViewFrame readFrame(Endpoint endpoint) {
        byte[] data = restTemplate.getForObject(endpoint.url(), byte[].class);
        return new LiveViewFrame(
                data == null ? new byte[0] : data,
                960,
                640,
                Instant.now(),
                MediaType.IMAGE_JPEG_VALUE);
    }

    private void awaitStartDelay() {
        if (startDelay.isZero()) {
            return;
        }
        try {
            waiter.await(startDelay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CameraOperationException(profileId, CANCELLED, null, interrupted);
        }
    }

    private CameraException liveViewFailure(String safeMessage, RuntimeException failure) {
        CameraOperationException typed = operationFailure(failure);
        return typed != null ? typed : new CameraException(safeMessage, failure);
    }

    private CameraOperationException operation(CameraOperationException.Reason reason) {
        return new CameraOperationException(profileId, reason, null);
    }

    private static CameraOperationException operationFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CameraOperationException operation) {
                return operation;
            }
            current = current.getCause();
        }
        return null;
    }

    private static CameraOperationException.Reason reason(RuntimeException failure) {
        CameraOperationException typed = operationFailure(failure);
        if (typed != null) {
            return typed.reason();
        }
        if (failure instanceof HttpServerErrorException.ServiceUnavailable unavailable) {
            String body = unavailable.getResponseBodyAsString();
            String normalized = body == null ? "" : body.toLowerCase(Locale.ROOT);
            if (normalized.contains("live view not started")) {
                return LIVE_VIEW_NOT_STARTED;
            }
            if (normalized.contains("already started")) {
                return ALREADY_STARTED;
            }
            if (normalized.contains("not started")) {
                return NOT_STARTED;
            }
            if (normalized.contains("during shooting or recording")) {
                return DURING_SHOOTING_OR_RECORDING;
            }
            if (normalized.contains("taken in preparation")) {
                return TAKEN_IN_PREPARATION;
            }
            if (normalized.contains("mode not supported")) {
                return MODE_NOT_SUPPORTED;
            }
            if (normalized.contains("device busy")) {
                return DEVICE_BUSY;
            }
            return SERVICE_UNAVAILABLE;
        }
        return null;
    }

    private static byte[] startBody(String size) {
        return ("{\"liveviewsize\":\"" + size
                + "\",\"cameradisplay\":\"on\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String normalizeSize(String requestedSize) {
        Objects.requireNonNull(requestedSize, "requestedSize");
        String normalized = requestedSize.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("small", "medium", "off").contains(normalized)) {
            throw new IllegalArgumentException(
                    "live-view size must be small, medium or off");
        }
        return normalized;
    }

    private static Comparator<Endpoint> endpointOrder() {
        return Comparator.comparingInt(Endpoint::versionNumber)
                .thenComparing(Endpoint::path);
    }

    private static String endpointPath(String url, String advertisedValue) {
        for (String candidate : List.of(url, advertisedValue)) {
            try {
                String path = URI.create(candidate).getPath();
                if (path != null && !path.isBlank()) {
                    return path;
                }
            } catch (IllegalArgumentException ignored) {
                // Try the next representation.
            }
        }
        return advertisedValue;
    }

    private static int versionNumber(String path) {
        Matcher matcher = VERSION.matcher(path);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String normalizeSuffix(String suffix) {
        Objects.requireNonNull(suffix, "suffix");
        String normalized = suffix.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String requireProfileId(String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        if (profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        return profileId;
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
