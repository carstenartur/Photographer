package com.photographer.focusstack.camera.discovery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CcapiDiscoveryServiceTest {

    @Test
    void discoversDeduplicatesAndParsesCamerasAcrossBindings() throws Exception {
        CcapiNetworkBinding ipv4 = loopbackBinding(CcapiAddressFamily.IPV4);
        CcapiNetworkBinding ipv6 = loopbackBinding(CcapiAddressFamily.IPV6);
        URI firstDescription = URI.create("http://127.0.0.1:49152/camera-a.xml");
        URI duplicateDescription = URI.create("http://127.0.0.1:49152/camera-a-v6.xml");

        CcapiSsdpTransport transport = (binding, request, timeout) -> {
            String requestText = new String(request, StandardCharsets.US_ASCII);
            assertTrue(requestText.contains("ST: " + CcapiSsdpCodec.SERVICE_TYPE));
            assertFalse(timeout.isZero());
            URI location = binding.family() == CcapiAddressFamily.IPV4
                    ? firstDescription : duplicateDescription;
            return List.of(response(location), irrelevantResponse());
        };

        Map<URI, byte[]> descriptions = Map.of(
                firstDescription, xml("uuid:same-camera", "Camera IPv4", "http://127.0.0.1:8080/ccapi"),
                duplicateDescription, xml("uuid:same-camera", "Camera IPv6", "http://[::1]:8080/ccapi")
        );
        CcapiDiscoveryService service = service(
                () -> List.of(ipv4, ipv6), transport, descriptions::get);

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
                irrelevantResponse(), response(good), response(bad));
        Map<URI, byte[]> descriptions = new LinkedHashMap<>();
        descriptions.put(good, xml("uuid:good", "Good Camera", "http://127.0.0.1:8080/ccapi"));
        descriptions.put(bad, "<not-xml".getBytes(StandardCharsets.UTF_8));

        CcapiDiscoveryResult result = service(
                () -> List.of(binding), transport, descriptions::get)
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
        URI description = URI.create("http://127.0.0.1/camera.xml");
        List<CcapiNetworkBinding> calls = new ArrayList<>();
        CcapiSsdpTransport transport = (binding, request, timeout) -> {
            calls.add(binding);
            if (binding.family() == CcapiAddressFamily.IPV4) {
                throw new IOException("socket unavailable");
            }
            return List.of(response(description));
        };

        CcapiDiscoveryResult result = service(
                () -> List.of(first, second), transport,
                uri -> xml("uuid:camera", "Camera", "http://127.0.0.1:8080/ccapi"))
                .discover(Duration.ofSeconds(2));

        assertEquals(2, calls.size());
        assertEquals(1, result.cameras().size());
        assertEquals(1, result.warnings().size());
        assertEquals("ssdp", result.warnings().getFirst().stage());
        assertTrue(result.warnings().getFirst().source().contains("127.0.0.1"));
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
    void rejectsNonPositiveTimeout() {
        CcapiDiscoveryService service = service(
                List::of,
                (binding, request, timeout) -> List.of(),
                (uri, timeout) -> new byte[0]);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.discover(Duration.ZERO));
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
        InetAddress address = InetAddress.getByName("127.0.0.1");
        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
        if (networkInterface == null) {
            networkInterface = NetworkInterface.getNetworkInterfaces().nextElement();
        }
        return new CcapiNetworkBinding(networkInterface, address, family);
    }

    private static byte[] response(URI location) {
        return ("HTTP/1.1 200 OK\r\n"
                + "LOCATION: " + location + "\r\n"
                + "ST: " + CcapiSsdpCodec.SERVICE_TYPE + "\r\n"
                + "USN: uuid:test::" + CcapiSsdpCodec.SERVICE_TYPE + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] irrelevantResponse() {
        return ("HTTP/1.1 200 OK\r\n"
                + "LOCATION: http://127.0.0.1/media.xml\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
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
