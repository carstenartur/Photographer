package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.common.model.CameraProfile;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Private, normalized camera identity used only as an in-process lease key.
 *
 * <p>The canonical origin is deliberately not exposed through {@link #toString()}.
 * Public diagnostics use only the one-way {@link #redactedId()}.</p>
 */
public final class CameraLeaseKey {
    private static final HexFormat HEX = HexFormat.of();
    static final CameraLeaseKey FIRST_CANON_USB_CAMERA = new CameraLeaseKey("canon-usb:first-device");

    private final String canonicalOrigin;
    private final String redactedId;

    private CameraLeaseKey(String canonicalOrigin) {
        this.canonicalOrigin = canonicalOrigin;
        this.redactedId = fingerprint(canonicalOrigin);
    }

    static CameraLeaseKey fromMockProfile(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Mock camera profile ID is required");
        }
        return new CameraLeaseKey("mock:" + id);
    }

    public static CameraLeaseKey fromCcapiProfile(CameraProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile.backendType() == null
                || !"canon-ccapi".equals(profile.backendType().trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Camera profile is not a canon-ccapi profile");
        }
        return fromCcapiLocation(profile.host());
    }

    public static CameraLeaseKey fromCcapiLocation(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("CCAPI camera location is required");
        }
        String trimmed = location.trim();
        boolean explicitScheme = hasHttpScheme(trimmed);
        if (!explicitScheme && hasExplicitUriScheme(trimmed)) {
            throw new IllegalArgumentException("CCAPI camera location must use HTTP or HTTPS");
        }
        URI uri;
        try {
            uri = URI.create(explicitScheme ? trimmed : "http://" + trimmed);
        } catch (IllegalArgumentException privateParseFailure) {
            throw new IllegalArgumentException("Malformed CCAPI camera location");
        }

        String scheme = normalizeScheme(uri.getScheme());
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Credentials are not allowed in a CCAPI camera location");
        }
        Authority authority = extractAuthority(uri);
        String normalizedHost = normalizeHost(authority.host());
        int effectivePort = authority.port();
        if (effectivePort < 0) {
            effectivePort = explicitScheme ? defaultPort(scheme) : 8080;
        }
        if (effectivePort < 1 || effectivePort > 65_535) {
            throw new IllegalArgumentException("CCAPI camera location contains an invalid port");
        }

        String printableHost = normalizedHost.indexOf(':') >= 0
                ? '[' + normalizedHost + ']'
                : normalizedHost;
        return new CameraLeaseKey(scheme + "://" + printableHost + ':' + effectivePort);
    }

    public String redactedId() {
        return redactedId;
    }

    private static boolean hasHttpScheme(String value) {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    private static boolean hasExplicitUriScheme(String value) {
        int separator = value.indexOf("://");
        if (separator <= 0 || !Character.isLetter(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < separator; index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character)
                    && character != '+' && character != '-' && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static String normalizeScheme(String scheme) {
        if (scheme == null) {
            throw new IllegalArgumentException("CCAPI camera location must contain a scheme");
        }
        String normalized = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalized) && !"https".equals(normalized)) {
            throw new IllegalArgumentException("CCAPI camera location must use HTTP or HTTPS");
        }
        return normalized;
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    private static Authority extractAuthority(URI uri) {
        if (uri.getHost() != null && !uri.getHost().isBlank()) {
            return new Authority(uri.getHost(), uri.getPort());
        }
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null || rawAuthority.isBlank() || rawAuthority.indexOf('@') >= 0) {
            throw new IllegalArgumentException("CCAPI camera location must contain a valid host");
        }

        if (rawAuthority.startsWith("[")) {
            int closingBracket = rawAuthority.indexOf(']');
            if (closingBracket < 2) {
                throw new IllegalArgumentException("Invalid IPv6 camera location");
            }
            String host = rawAuthority.substring(1, closingBracket);
            String remainder = rawAuthority.substring(closingBracket + 1);
            return new Authority(host, parseOptionalPort(remainder));
        }

        int colon = rawAuthority.lastIndexOf(':');
        if (colon > 0 && rawAuthority.indexOf(':') == colon) {
            String portText = rawAuthority.substring(colon + 1);
            if (portText.chars().allMatch(Character::isDigit)) {
                return new Authority(
                        rawAuthority.substring(0, colon), parsePort(portText));
            }
            throw new IllegalArgumentException("CCAPI camera location contains an invalid port");
        }
        if (rawAuthority.indexOf(':') >= 0) {
            throw new IllegalArgumentException("IPv6 camera locations must use brackets");
        }
        return new Authority(rawAuthority, -1);
    }

    private static int parseOptionalPort(String remainder) {
        if (remainder.isEmpty()) {
            return -1;
        }
        if (!remainder.startsWith(":") || remainder.length() == 1) {
            throw new IllegalArgumentException("CCAPI camera location contains an invalid port");
        }
        return parsePort(remainder.substring(1));
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException privatePortFailure) {
            throw new IllegalArgumentException("CCAPI camera location contains an invalid port");
        }
    }

    private static String normalizeHost(String host) {
        String value = stripBrackets(host).toLowerCase(Locale.ROOT);
        if (value.indexOf('%') >= 0) {
            throw new IllegalArgumentException("Scoped IPv6 camera locations are not supported");
        }
        if (value.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(value);
                if (!(address instanceof Inet6Address)) {
                    throw new IllegalArgumentException("Invalid IPv6 camera location");
                }
                return address.getHostAddress().toLowerCase(Locale.ROOT);
            } catch (UnknownHostException privateHostFailure) {
                throw new IllegalArgumentException("Invalid IPv6 camera location");
            }
        }
        try {
            String ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
            return ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
        } catch (IllegalArgumentException privateHostFailure) {
            throw new IllegalArgumentException("Invalid CCAPI camera host");
        }
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    String canonicalOriginForTesting() {
        return canonicalOrigin;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CameraLeaseKey key
                && canonicalOrigin.equals(key.canonicalOrigin);
    }

    @Override
    public int hashCode() {
        return canonicalOrigin.hashCode();
    }

    @Override
    public String toString() {
        return "CameraLeaseKey[" + redactedId + ']';
    }

    private record Authority(String host, int port) {
        private Authority {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException(
                        "CCAPI camera location must contain a valid host");
            }
        }
    }
}
