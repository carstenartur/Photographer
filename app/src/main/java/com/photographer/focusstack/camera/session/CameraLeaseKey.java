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

    private final String canonicalOrigin;
    private final String redactedId;

    private CameraLeaseKey(String canonicalOrigin) {
        this.canonicalOrigin = canonicalOrigin;
        this.redactedId = fingerprint(canonicalOrigin);
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
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("CCAPI camera location must contain a valid host");
        }
        String normalizedHost = normalizeHost(host);
        int effectivePort = uri.getPort();
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
            return IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
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
}
