package com.photographer.focusstack.camera.discovery;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CcapiDiscoverySecurityTest {

    @Test
    void warningSourceDropsQueryAndFragment() {
        assertEquals(
                "http://127.0.0.1:49152/device.xml",
                CcapiDiscoveryService.redactedUriSource(
                        URI.create("http://127.0.0.1:49152/device.xml?token=secret#part")));
    }

    @Test
    void deviceDescriptionWarningDoesNotExposeLocationQuery() throws Exception {
        CcapiNetworkBinding binding = loopbackBinding();
        URI description = URI.create(
                "http://127.0.0.1/device.xml?temporary-token=secret");
        CcapiDiscoveryService service = new CcapiDiscoveryService(
                () -> List.of(binding),
                (ignored, request, timeout) -> List.of(datagram(description)),
                (uri, timeout) -> "<broken".getBytes(StandardCharsets.UTF_8),
                new CcapiDeviceDescriptionParser());

        CcapiDiscoveryResult result = service.discover(Duration.ofSeconds(1));

        assertEquals(1, result.warnings().size());
        assertEquals("http://127.0.0.1/device.xml",
                result.warnings().getFirst().source());
        assertFalse(result.warnings().getFirst().source().contains("secret"));
    }

    @Test
    void deduplicatesUdnWithoutCaseSensitivity() throws Exception {
        CcapiNetworkBinding binding = loopbackBinding();
        URI first = URI.create("http://127.0.0.1/first.xml");
        URI second = URI.create("http://127.0.0.1/second.xml");
        Map<URI, byte[]> descriptions = Map.of(
                first, xml("UUID:Camera-One", "First"),
                second, xml("uuid:camera-one", "Second"));
        CcapiDiscoveryService service = new CcapiDiscoveryService(
                () -> List.of(binding),
                (ignored, request, timeout) -> List.of(datagram(first), datagram(second)),
                (uri, timeout) -> descriptions.get(uri),
                new CcapiDeviceDescriptionParser());

        CcapiDiscoveryResult result = service.discover(Duration.ofSeconds(1));

        assertEquals(1, result.cameras().size());
        assertEquals("First", result.cameras().getFirst().friendlyName());
        assertTrue(result.warnings().isEmpty());
    }

    private static CcapiNetworkBinding loopbackBinding() throws Exception {
        InetAddress address = InetAddress.getByName("127.0.0.1");
        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
        if (networkInterface == null) {
            networkInterface = NetworkInterface.getNetworkInterfaces().nextElement();
        }
        return new CcapiNetworkBinding(
                networkInterface, address, CcapiAddressFamily.IPV4);
    }

    private static CcapiSsdpDatagram datagram(URI location) {
        String payload = "HTTP/1.1 200 OK\r\n"
                + "LOCATION: " + location + "\r\n"
                + "ST: " + CcapiSsdpCodec.SERVICE_TYPE + "\r\n\r\n";
        return new CcapiSsdpDatagram(
                payload.getBytes(StandardCharsets.ISO_8859_1),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 1900));
    }

    private static byte[] xml(String udn, String name) {
        return ("""
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <device>
                    <friendlyName>%s</friendlyName>
                    <modelName>Canon Test</modelName>
                    <UDN>%s</UDN>
                    <serviceList><service>
                      <serviceType>%s</serviceType>
                      <ns:X_onService xmlns:ns="urn:schemas-canon-com:schema-upnp">1</ns:X_onService>
                      <ns:X_accessURL xmlns:ns="urn:schemas-canon-com:schema-upnp">http://127.0.0.1:8080/ccapi</ns:X_accessURL>
                    </service></serviceList>
                  </device>
                </root>
                """).formatted(name, udn, CcapiSsdpCodec.SERVICE_TYPE)
                .getBytes(StandardCharsets.UTF_8);
    }
}
