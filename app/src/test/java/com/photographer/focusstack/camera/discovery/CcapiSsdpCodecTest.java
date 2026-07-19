package com.photographer.focusstack.camera.discovery;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CcapiSsdpCodecTest {

    @Test
    void buildsCanonicalIpv4MSearchRequest() {
        String expected = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 1\r\n"
                + "ST: " + CcapiSsdpCodec.SERVICE_TYPE + "\r\n\r\n";

        assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII),
                CcapiSsdpCodec.mSearchRequest(CcapiAddressFamily.IPV4));
    }

    @Test
    void buildsCanonicalIpv6MSearchRequest() {
        String request = new String(
                CcapiSsdpCodec.mSearchRequest(CcapiAddressFamily.IPV6),
                StandardCharsets.US_ASCII);

        assertTrue(request.contains("HOST: [FF02::C]:1900\r\n"));
        assertTrue(request.endsWith("\r\n\r\n"));
    }

    @Test
    void parsesCanonMSearchResponseCaseInsensitively() {
        String packet = "HTTP/1.1 200 OK\r\n"
                + "cache-control: max-age=1800\r\n"
                + "LOCATION: http://192.168.1.2:49152/upnp/CameraDevDesc.xml\r\n"
                + "St: urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1\r\n"
                + "USN: uuid:camera::urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1\r\n"
                + "\r\n";

        CcapiSsdpMessage message = CcapiSsdpCodec.parse(packet).orElseThrow();

        assertTrue(message.isCanonCcapi());
        assertEquals(URI.create("http://192.168.1.2:49152/upnp/CameraDevDesc.xml"),
                message.location().orElseThrow());
        assertEquals("max-age=1800", message.header("Cache-Control").orElseThrow());
    }

    @Test
    void acceptsCanonAdvertisementUsingNtOrUsn() {
        String packet = "NOTIFY * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "NT: urn:schemas-canon-com:device:ICPO-CameraControlAPIService:1\r\n"
                + "NTS: ssdp:alive\r\n"
                + "USN: uuid:camera::urn:schemas-canon-com:device:ICPO-CameraControlAPIService:1\r\n"
                + "LOCATION: http://10.0.0.2/device.xml\r\n\r\n";

        assertTrue(CcapiSsdpCodec.parse(packet).orElseThrow().isCanonCcapi());
    }

    @Test
    void rejectsIrrelevantPacketsAndUnsafeLocations() {
        String irrelevant = "HTTP/1.1 200 OK\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n"
                + "LOCATION: http://10.0.0.5/device.xml\r\n\r\n";
        CcapiSsdpMessage message = CcapiSsdpCodec.parse(irrelevant).orElseThrow();
        assertFalse(message.isCanonCcapi());

        String credentials = "HTTP/1.1 200 OK\r\n"
                + "ST: " + CcapiSsdpCodec.SERVICE_TYPE + "\r\n"
                + "LOCATION: http://user:password@10.0.0.2/device.xml\r\n\r\n";
        assertTrue(CcapiSsdpCodec.parse(credentials).orElseThrow().location().isEmpty());

        String fragment = "HTTP/1.1 200 OK\r\n"
                + "ST: " + CcapiSsdpCodec.SERVICE_TYPE + "\r\n"
                + "LOCATION: http://10.0.0.2/device.xml#ignored\r\n\r\n";
        assertTrue(CcapiSsdpCodec.parse(fragment).orElseThrow().location().isEmpty());

        assertTrue(CcapiSsdpCodec.parse("garbage").isEmpty());
        assertTrue(CcapiSsdpCodec.parse(new byte[2], 3).isEmpty());
    }
}
