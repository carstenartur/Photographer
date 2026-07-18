package com.photographer.focusstack.camera.discovery;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Parsed SSDP response or advertisement with case-insensitive header access. */
public record CcapiSsdpMessage(String startLine, Map<String, String> headers) {

    public CcapiSsdpMessage {
        startLine = Objects.requireNonNull(startLine, "startLine").trim();
        Objects.requireNonNull(headers, "headers");
        Map<String, String> normalized = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (name != null && value != null) {
                normalized.put(name.trim().toLowerCase(Locale.ROOT), value.trim());
            }
        });
        headers = Map.copyOf(normalized);
    }

    public Optional<String> header(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(headers.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<URI> location() {
        return header("location").flatMap(CcapiSsdpMessage::safeUri);
    }

    /** Canon requires the service identifier in ST/NT or USN. */
    public boolean isCanonCcapi() {
        return header("st").map(CcapiSsdpCodec::isCanonCcapiService).orElse(false)
                || header("nt").map(CcapiSsdpCodec::isCanonCcapiService).orElse(false)
                || header("usn").map(CcapiSsdpCodec::isCanonCcapiService).orElse(false);
    }

    private static Optional<URI> safeUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null) {
                return Optional.empty();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            return "http".equals(scheme) || "https".equals(scheme)
                    ? Optional.of(uri) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
