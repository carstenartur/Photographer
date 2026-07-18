package com.photographer.focusstack.camera.discovery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CcapiDiscoveryServiceTest {

    @Test
    void discoversDeduplicatesAndParsesCamerasAcrossBindings() throws Exception {
        CcapiNetworkBinding ipv4 = loopbackBinding(CcapiAddressFamily.IPV4);
        CcapiNetworkBinding ipv6 = loopbackBinding(CcapiAddressFamily.IPV6);
        URI firstDescription = URI.create("http://127.0.0.1:49152/camera-a.xml");
        URI duplicateDescription = URI.create("http://[::1]:49152/camera-a-v6.xml");

        CcapiSsdpTransport transport = (binding, request, timeout) -> {
            String requestText = new String(request, StandardCharsets.US_ASCII);
            assertTrue(requestText.contains("ST: " + CcapiSsdpCodec.SERVICE_TYPE));
            assertFalse(timeout.isZero());
            boolean isIpv4 = binding.family() == CcapiAddressFamily.IPV4;
            URI location = isIpv4 ? firstDescription : duplicateDescription;
            String source = isIpv4 ? "127.0.0.1" : "::1";
            return List.of(response(location, source), irrelevantResponse(source));
        };

        Map<URI, byte[]> descriptions = Map.of(
                firstDescription, xml("uuid:same-camera", "Camera IPv4", "http://127.0.0.1:8080/ccapi"),
                duplicateDescription, xml("uuid:same-camera", "Camera IPv6", "http://[::1]:8080/ccapi")
        );
        CcapiDiscoveryService service = service(
                () -> List.of(ipv4, ipv6), transport,
                (uri, timeout) -> descriptions.get(uri));

        CcapiDiscoveryResult result = service.discover(Duration.ofSeconds(2));

        assertEquals(1, result.cameras().size());
        assertEquals("uuid:same-camera", result.cameras().getFirst().udn());
        assertEquals("Camera IPv4", result.cameras().getFirst().friendlyName());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void ignoresIrrelevantPacketsAndReportsBadDescriptionsWithoutLosingGoodCamera()
            throws Exception {
        CcapiNetworkBinding binding = loopbackBinding(CcapiAddressFamily.IPV4);
        URI good = URI.create("http://127.0.0.1/good.xml");
        URI bad = URI.create("http://127.0.0.1/bad.xml");
        CcapiSsdpTransport transport = (ignoredBinding, request, timeout) -> List.of(
                irrelevantResponse("127.0.0.1"),
                response(good, "127.0.0.1"),
                response(bad, "127.0.0.1"));
        Map<URI, byte[]> descriptions = new LinkedHashMap<>();
        descriptions.put(good, xml("uuid:good", "Good Camera", "http://127.0.0.1:8080/ccapi"));
        descriptions.put(bad, "<not-xml".getBytes(StandardCharsets.UTF_8));

        CcapiDiscoveryResult result = service(
                () -> List.of(binding), transport,
                (uri, timeout) -> descriptions.get(uri))
                .discover(Duration.ofSeconds(2));

        assertEquals(1, result.cameras().size());
        assertEquals("uuid:good", result.cameras().getFirst().udn());
        assertEquals(1, result.warnings().size());
        assertEquals("device-description", result.warnings().getFirst().stage());
        assertEquals(bad.toString(), result.warnings().getFirst().source());
        assertFalse(result.warnings().getFirst().message().contains("<not-xml"));
    }

    @Test
    void reportsTransportFailureAndContinuesWithOtherBinding() throws Exception {
        CcapiNetworkBinding first = loopbackBinding(CcapiAddressFamily.IPV4);
        CcapiNetworkBinding second = loopbackBinding(CcapiAddressFamily.IPV6);
        URI description = URI.create("http://[::1]/camera.xml");
        List<CcapiNetworkBinding> calls = new ArrayList<>();
        CcapiSsdpTransport transport = (binding, request, timeout) -> {
            calls.add(binding);
            if (binding.family() == CcapiAddressFamily.IPV4) {
                throw new IOException("socket unavailable");
            }
            return List.of(response(description, "::1"));
        };

        CcapiDiscoveryResult result = service(
                () -> List.of(first, second), transport,
                (uri, timeout) -> xml(
                        "uuid:camera", "Camera", "http://[::1]:8080/ccapi"))
                .discover(Duration.ofSeconds(2));

        assertEquals(2, calls.size());
        assertEquals(1, result.cameras().size());
        assertEquals(1, result.warnings().size());
        assertEquals("ssdp", result.warnings().getFirst().stage());
        assertTrue(result.warnings().getFirst().source().contains("127.0.0.1"));
    }

    @Test
    void rejectsLocationWhoseHostDiffersFromDatagramSource() throws Exception {
        CcapiNetworkBinding binding = loopbackBinding(CcapiAddressFamily.IPV4);
        AtomicInteger fetches = new AtomicInteger();
        CcapiSsdpTransport transport = (ignoredBinding, request, timeout) -> List.of(
                response(URI.create("http://10.0.0.25/device.xml"), "127.0.0.1"));

        CcapiDiscoveryResult result = service(
                () -> List.of(binding), transport,
                (uri, timeout) -> {
                    fetches.incrementAndGet();
                    return new byte[0];
                }).discover(Duration.ofSeconds(1));

        assertTrue(result.cameras().isEmpty());
        assertEquals(0, fetches.get());
        assertEquals(1, result.warnings().size());
        assertEquals("ssdp-location", result.warnings().getFirst().stage());
        assertFalse(result.warnings().getFirst().message().contains("10.0.0.25"));
    }

    @Test
    void validatesNumericLocationHostsAgainstSource() throws Exception {
        assertTrue(CcapiDiscoveryService.locationMatchesSource(
                URI.create("http://127.0.0.1/device.xml"),
                InetAddress.getByName("127.0.0.1")));
        assertTrue(CcapiDiscoveryService.locationMatchesSource(
                URI.create("http://[::1]/device.xml"),
                InetAddress.getByName("::1")));
        assertFalse(CcapiDiscoveryService.locationMatchesSource(
                URI.create("http://camera.local/device.xml"),
                InetAddress.getByName("127.0.0.1")));
        assertFalse(CcapiDiscoveryService.locationMatchesSource(
                URI.create("http://127.0.0.2/device.xml"),
                InetAddress.getByName("127.0.0.1")));
    }

    @Test
    void reportsWhenNoEligibleBindingExists() {
        CcapiDiscoveryResult result = service(
                List::of,
                (binding, request, timeout) -> List.of(),
                (uri, timeout) -> new byte[0])
                .discover(Duration.ofMillis(500));

        assertTrue(result.cameras().isEmpty());
        assertEquals(1, result.warnings().size());
        assertEquals("network-interfaces", result.warnings().getFirst().stage());
    }

    @Test
    void rejectsUnboundedOrNonPositiveTimeout() {
        CcapiDiscoveryService service = service(
                List::of,
                (binding, request, timeout) -> List.of(),
                (uri, timeout) -> new byte[0]);

        assertThrows(IllegalArgumentException.class,
                () -> service.discover(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> service.discover(Duration.ofMinutes(6)));
    }

    private static CcapiDiscoveryService service(
            CcapiNetworkBindingProvider provider,
            CcapiSsdpTransport transport,
            CcapiDeviceDescriptionFetcher fetcher) {
        return new CcapiDiscoveryService(
                provider, transport, fetcher, new CcapiDeviceDescriptionParser());
    }

    private static CcapiNetworkBinding loopbackBinding(CcapiAddressFamily family)
            throws Exception {
        InetAddress address = InetAddress.getByName(
                family == CcapiAddressFamily.IPV4 ? "127.0.0.1" : "::1");
        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
        if (networkInterface == null) {
            networkInterface = NetworkInterface.getNetworkInterfaces().nextElement();
        }
        return new CcapiNetworkBinding(networkInterface, address, family);
    }

    private static CcapiSsdpDatagram response(URI location, String source) {
        byte[] payload = ("HTTP/1.1 200 OK\r\n"
                + "LOCATION: " + location + "\r\n"
                + "ST: " + CcapiSsdpCodec.SERVICE_TYPE + "\r\n"
                + "USN: uuid:test::" + CcapiSsdpCodec.SERVICE_TYPE + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        return datagram(payload, source);
    }

    private static CcapiSsdpDatagram irrelevantResponse(String source) {
        byte[] payload = ("HTTP/1.1 200 OK\r\n"
                + "LOCATION: http://127.0.0.1/media.xml\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        return datagram(payload, source);
    }

    private static CcapiSsdpDatagram datagram(byte[] payload, String source) {
        try {
            return new CcapiSsdpDatagram(
                    payload, new InetSocketAddress(InetAddress.getByName(source), 1900));
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static byte[] xml(String udn, String name, String accessUrl) {
        String xml = """
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <device>
                    <friendlyName>%s</friendlyName>
                    <modelName>Canon Test</modelName>
                    <serialNumber>123</serialNumber>
                    <UDN>%s</UDN>
                    <serviceList><service>
                      <serviceType>%s</serviceType>
                      <ns:X_onService xmlns:ns="urn:schemas-canon-com:schema-upnp">1</ns:X_onService>
                      <ns:X_accessURL xmlns:ns="urn:schemas-canon-com:schema-upnp">%s</ns:X_accessURL>
                      <ns:X_deviceNickname xmlns:ns="urn:schemas-canon-com:schema-upnp">Test</ns:X_deviceNickname>
                    </service></serviceList>
                  </device>
                </root>
                """.formatted(name, udn, CcapiSsdpCodec.SERVICE_TYPE, accessUrl);
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
