package com.photographer.focusstack.camera.discovery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Performs one bounded Canon CCAPI discovery pass on all eligible local interfaces.
 *
 * <p>The service has no Android dependency and never assumes names such as
 * {@code wlan0}. SSDP packets and device-description bodies are parsed into
 * structured data and are not logged or returned as diagnostics.</p>
 */
public final class CcapiDiscoveryService {

    private static final Duration MIN_OPERATION_SLICE = Duration.ofMillis(250);
    private static final Duration MAX_DISCOVERY_TIMEOUT = Duration.ofMinutes(5);
    private final CcapiNetworkBindingProvider bindingProvider;
    private final CcapiSsdpTransport ssdpTransport;
    private final CcapiDeviceDescriptionFetcher descriptionFetcher;
    private final CcapiDeviceDescriptionParser descriptionParser;

    public CcapiDiscoveryService() {
        this(new JdkCcapiNetworkBindingProvider(),
                new UdpCcapiSsdpTransport(),
                new HttpCcapiDeviceDescriptionFetcher(),
                new CcapiDeviceDescriptionParser());
    }

    CcapiDiscoveryService(CcapiNetworkBindingProvider bindingProvider,
                          CcapiSsdpTransport ssdpTransport,
                          CcapiDeviceDescriptionFetcher descriptionFetcher,
                          CcapiDeviceDescriptionParser descriptionParser) {
        this.bindingProvider = Objects.requireNonNull(bindingProvider, "bindingProvider");
        this.ssdpTransport = Objects.requireNonNull(ssdpTransport, "ssdpTransport");
        this.descriptionFetcher = Objects.requireNonNull(descriptionFetcher, "descriptionFetcher");
        this.descriptionParser = Objects.requireNonNull(descriptionParser, "descriptionParser");
    }

    public CcapiDiscoveryResult discover(Duration timeout) {
        validateTimeout(timeout);
        long deadline = deadline(timeout);
        List<CcapiDiscoveryWarning> warnings = new ArrayList<>();
        List<CcapiNetworkBinding> bindings;
        try {
            bindings = bindingProvider.bindings();
        } catch (SocketException exception) {
            warnings.add(warning("network-interfaces", null, exception));
            return new CcapiDiscoveryResult(List.of(), warnings);
        }
        if (bindings.isEmpty()) {
            warnings.add(new CcapiDiscoveryWarning(
                    "network-interfaces", null, "No eligible multicast-capable interface found"));
            return new CcapiDiscoveryResult(List.of(), warnings);
        }

        Set<URI> descriptionUrls = discoverDescriptionUrls(bindings, deadline, warnings);
        Map<String, CcapiDiscoveredCamera> cameras = new LinkedHashMap<>();
        int remainingLocations = descriptionUrls.size();
        for (URI descriptionUrl : descriptionUrls) {
            Duration remaining = remaining(deadline);
            if (remaining.isZero()) {
                warnings.add(new CcapiDiscoveryWarning(
                        "device-description", null, "Discovery deadline reached"));
                break;
            }
            Duration slice = operationSlice(remaining, remainingLocations--);
            try {
                byte[] xml = descriptionFetcher.fetch(descriptionUrl, slice);
                CcapiDiscoveredCamera camera = descriptionParser.parse(
                        new ByteArrayInputStream(xml), descriptionUrl);
                cameras.putIfAbsent(cameraIdentity(camera), camera);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                warnings.add(new CcapiDiscoveryWarning(
                        "device-description", descriptionUrl.toString(), "Discovery interrupted"));
                break;
            } catch (IOException | RuntimeException exception) {
                warnings.add(warning(
                        "device-description", descriptionUrl.toString(), exception));
            }
        }
        return new CcapiDiscoveryResult(List.copyOf(cameras.values()), warnings);
    }

    private Set<URI> discoverDescriptionUrls(List<CcapiNetworkBinding> bindings,
                                             long deadline,
                                             List<CcapiDiscoveryWarning> warnings) {
        Set<URI> result = new LinkedHashSet<>();
        int remainingBindings = bindings.size();
        for (CcapiNetworkBinding binding : bindings) {
            Duration remaining = remaining(deadline);
            if (remaining.isZero()) {
                warnings.add(new CcapiDiscoveryWarning(
                        "ssdp", null, "Discovery deadline reached"));
                break;
            }
            Duration slice = operationSlice(remaining, remainingBindings--);
            byte[] request = CcapiSsdpCodec.mSearchRequest(binding.family());
            try {
                for (byte[] packet : ssdpTransport.search(binding, request, slice)) {
                    CcapiSsdpCodec.parse(packet, packet.length)
                            .filter(CcapiSsdpMessage::isCanonCcapi)
                            .flatMap(CcapiSsdpMessage::location)
                            .ifPresent(result::add);
                }
            } catch (IOException | RuntimeException exception) {
                String source = binding.displayName() + " "
                        + binding.localAddress().getHostAddress();
                warnings.add(warning("ssdp", source, exception));
            }
        }
        return result;
    }

    private static String cameraIdentity(CcapiDiscoveredCamera camera) {
        return camera.udn() != null ? "udn:" + camera.udn()
                : "url:" + camera.accessUrl().normalize();
    }

    private static Duration operationSlice(Duration remaining, int operationsLeft) {
        if (operationsLeft <= 1) {
            return remaining;
        }
        Duration fairShare = remaining.dividedBy(operationsLeft);
        if (fairShare.compareTo(MIN_OPERATION_SLICE) < 0) {
            return remaining.compareTo(MIN_OPERATION_SLICE) < 0
                    ? remaining : MIN_OPERATION_SLICE;
        }
        return fairShare;
    }

    private static long deadline(Duration timeout) {
        return Math.addExact(System.nanoTime(), timeout.toNanos());
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0L ? Duration.ZERO : Duration.ofNanos(nanos);
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (timeout.compareTo(MAX_DISCOVERY_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must not exceed 5 minutes");
        }
    }

    private static CcapiDiscoveryWarning warning(
            String stage, String source, Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return new CcapiDiscoveryWarning(stage, source, message);
    }
}
