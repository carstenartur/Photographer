package com.photographer.focusstack.camera.discovery;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CcapiDeviceDescriptionParserTest {

    private final CcapiDeviceDescriptionParser parser = new CcapiDeviceDescriptionParser();

    @Test
    void parsesNamespacedCanonDeviceDescriptionStructurally() throws Exception {
        URI descriptionUrl = URI.create(
                "http://192.168.1.2:49152/upnp/CameraDevDesc.xml");
        String xml = """
                <?xml version="1.0"?>
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <device>
                    <deviceType>urn:schemas-canon-com:device:ICPO-CameraControlAPIService:1</deviceType>
                    <friendlyName>Canon EOS R</friendlyName>
                    <modelName>EOS R</modelName>
                    <serialNumber>012345678901</serialNumber>
                    <UDN>uuid:camera-123</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:Other:1</serviceType>
                      </service>
                      <service>
                        <serviceType>urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1</serviceType>
                        <ns:X_onService xmlns:ns="urn:schemas-canon-com:schema-upnp">1</ns:X_onService>
                        <ns:X_accessURL xmlns:ns="urn:schemas-canon-com:schema-upnp">/ccapi</ns:X_accessURL>
                        <ns:X_deviceNickname xmlns:ns="urn:schemas-canon-com:schema-upnp">Studio Camera</ns:X_deviceNickname>
                      </service>
                    </serviceList>
                  </device>
                </root>
                """;

        CcapiDiscoveredCamera camera = parser.parse(stream(xml), descriptionUrl);

        assertEquals(descriptionUrl, camera.descriptionUrl());
        assertEquals(URI.create("http://192.168.1.2:49152/ccapi"), camera.accessUrl());
        assertEquals("uuid:camera-123", camera.udn());
        assertEquals("Canon EOS R", camera.friendlyName());
        assertEquals("EOS R", camera.modelName());
        assertEquals("012345678901", camera.serialNumber());
        assertEquals("Studio Camera", camera.nickname());
        assertEquals(true, camera.serviceConnected());
    }

    @Test
    void preservesAbsoluteAccessUrlWithDifferentPort() throws Exception {
        String xml = description(
                "http://192.168.1.2:8080/ccapi", "0");

        CcapiDiscoveredCamera camera = parser.parse(
                stream(xml), URI.create("http://192.168.1.2:49152/device.xml"));

        assertEquals(URI.create("http://192.168.1.2:8080/ccapi"), camera.accessUrl());
        assertFalse(camera.serviceConnected());
    }

    @Test
    void rejectsDoctypeAndExternalEntityInput() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <device>
                    <friendlyName>&xxe;</friendlyName>
                    <serviceList><service>
                      <serviceType>urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1</serviceType>
                      <ns:X_accessURL xmlns:ns="urn:schemas-canon-com:schema-upnp">http://camera/ccapi</ns:X_accessURL>
                    </service></serviceList>
                  </device>
                </root>
                """;

        assertThrows(IOException.class, () -> parser.parse(
                stream(xml), URI.create("http://camera/device.xml")));
    }

    @Test
    void rejectsMissingServiceAndCredentialBearingAccessUrl() {
        String noService = """
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <device><friendlyName>Camera</friendlyName></device>
                </root>
                """;
        assertThrows(IOException.class, () -> parser.parse(
                stream(noService), URI.create("http://camera/device.xml")));

        String credentials = description(
                "http://user:secret@camera/ccapi", "1");
        assertThrows(IOException.class, () -> parser.parse(
                stream(credentials), URI.create("http://camera/device.xml")));
    }

    private static String description(String accessUrl, String onService) {
        return """
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <device>
                    <friendlyName>Camera</friendlyName>
                    <modelName>Model</modelName>
                    <UDN>uuid:test</UDN>
                    <serviceList><service>
                      <serviceType>urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1</serviceType>
                      <ns:X_onService xmlns:ns="urn:schemas-canon-com:schema-upnp">%s</ns:X_onService>
                      <ns:X_accessURL xmlns:ns="urn:schemas-canon-com:schema-upnp">%s</ns:X_accessURL>
                    </service></serviceList>
                  </device>
                </root>
                """.formatted(onService, accessUrl);
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
