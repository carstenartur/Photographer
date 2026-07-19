package com.photographer.focusstack.camera.discovery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static final int MAX_WARNING_MESSAGE_LENGTH = 256;
    private static final Pattern URL_QUERY_PATTERN = Pattern.compile(
            "(?i)(https?://[^\\s?#]+)\\?[^\\s#]+");
    private static final Pattern SENSITIVE_VALUE_PATTERN = Pattern.compile(
            "(?i)(password|passwd|token|secret|authorization|api[_-]?key)=([^\\s&,;]+)");

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
            String warningSource = redactedUriSource(descriptionUrl);
            try {
                byte[] xml = descriptionFetcher.fetch(descriptionUrl, slice);
                CcapiDiscoveredCamera camera = descriptionParser.parse(
                        new ByteArrayInputStream(xml), descriptionUrl);
                cameras.putIfAbsent(cameraIdentity(camera), camera);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                warnings.add(new CcapiDiscoveryWarning(
                        "device-description", warningSource, "Discovery interrupted"));
                break;
            } catch (IOException | RuntimeException exception) {
                warnings.add(warning(
                        "device-description", warningSource, exception));
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
                for (CcapiSsdpDatagram datagram : ssdpTransport.search(binding, request, slice)) {
                    byte[] payload = datagram.payload();
                    CcapiSsdpCodec.parse(payload, payload.length)
                            .filter(CcapiSsdpMessage::isCanonCcapi)
                            .flatMap(CcapiSsdpMessage::location)
                            .ifPresent(location -> addValidatedLocation(
                                    result, warnings, datagram, location));
                }
            } catch (IOException | RuntimeException exception) {
                String source = binding.displayName() + " "
                        + binding.localAddress().getHostAddress();
                warnings.add(warning("ssdp", source, exception));
            }
        }
        return result;
    }

    private static void addValidatedLocation(Set<URI> result,
                                             List<CcapiDiscoveryWarning> warnings,
                                             CcapiSsdpDatagram datagram,
                                             URI location) {
        if (locationMatchesSource(location, datagram.source().getAddress())) {
            result.add(location);
            return;
        }
        warnings.add(new CcapiDiscoveryWarning(
                "ssdp-location",
                datagram.source().getAddress().getHostAddress(),
                "Ignored LOCATION whose numeric host differs from the datagram source"));
    }

    static boolean locationMatchesSource(URI location, InetAddress source) {
        if (location == null || source == null || location.getHost() == null) {
            return false;
        }
        String numericHost = normalizeNumericHost(location.getHost());
        if (numericHost == null) {
            return false;
        }
        try {
            InetAddress locationAddress = InetAddress.getByName(numericHost);
            return Arrays.equals(locationAddress.getAddress(), source.getAddress());
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static String normalizeNumericHost(String host) {
        String value = host;
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        if (value.contains(":")) {
            return value.matches("[0-9A-Fa-f:.]+") ? value : null;
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        for (String octet : octets) {
            try {
                int number = Integer.parseInt(octet);
                if (octet.isEmpty() || number < 0 || number > 255) {
                    return null;
                }
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return value;
    }

    private static String cameraIdentity(CcapiDiscoveredCamera camera) {
        if (camera.udn() != null) {
            return "udn:" + camera.udn().toLowerCase(Locale.ROOT);
        }
        return "url:" + camera.accessUrl().normalize();
    }

    static String redactedUriSource(URI uri) {
        if (uri == null) {
            return null;
        }
        try {
            return new URI(
                    uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toString();
        } catch (URISyntaxException exception) {
            return uri.getHost();
        }
    }

    static String safeWarningMessage(Exception exception) {
        Objects.requireNonNull(exception, "exception");
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        message = URL_QUERY_PATTERN.matcher(message).replaceAll("$1?<redacted>");
        message = SENSITIVE_VALUE_PATTERN.matcher(message).replaceAll("$1=<redacted>");
        if (message.length() > MAX_WARNING_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_WARNING_MESSAGE_LENGTH - 1) + "…";
        }
        return message;
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
        return new CcapiDiscoveryWarning(
                stage, source, safeWarningMessage(exception));
    }
}
