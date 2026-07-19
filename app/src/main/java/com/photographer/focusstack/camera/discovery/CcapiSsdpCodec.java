package com.photographer.focusstack.camera.discovery;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Builds and parses the SSDP messages used by Canon CCAPI discovery. */
public final class CcapiSsdpCodec {

    public static final String SERVICE_TYPE =
            "urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1";
    private static final String SERVICE_TOKEN = "icpo-cameracontrolapiservice:1";

    private CcapiSsdpCodec() {
    }

    public static byte[] mSearchRequest(CcapiAddressFamily family) {
        String request = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + family.hostHeader() + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 1\r\n"
                + "ST: " + SERVICE_TYPE + "\r\n"
                + "\r\n";
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    public static Optional<CcapiSsdpMessage> parse(byte[] packet, int length) {
        if (packet == null || length <= 0 || length > packet.length) {
            return Optional.empty();
        }
        return parse(new String(packet, 0, length, StandardCharsets.ISO_8859_1));
    }

    public static Optional<CcapiSsdpMessage> parse(String packet) {
        if (packet == null || packet.isBlank()) {
            return Optional.empty();
        }
        String[] lines = packet.replace("\r\n", "\n").split("\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            return Optional.empty();
        }

        String startLine = lines[0].trim();
        boolean response = startLine.regionMatches(true, 0, "HTTP/1.1 200", 0, 12);
        boolean advertisement = startLine.regionMatches(true, 0, "NOTIFY * HTTP/1.1", 0, 17);
        if (!response && !advertisement) {
            return Optional.empty();
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                break;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            headers.merge(name, value, (first, second) -> first + "," + second);
        }
        return Optional.of(new CcapiSsdpMessage(startLine, headers));
    }

    static boolean isCanonCcapiService(String value) {
        return value != null
                && value.toLowerCase(Locale.ROOT).contains(SERVICE_TOKEN);
    }
}
