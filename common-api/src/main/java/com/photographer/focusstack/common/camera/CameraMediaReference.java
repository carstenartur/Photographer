package com.photographer.focusstack.common.camera;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Vendor-neutral reference to one camera media object.
 *
 * <p>The reference contains a normalized relative camera API path only. Absolute
 * transport URLs, camera addresses and authentication material are deliberately
 * excluded.</p>
 */
public record CameraMediaReference(
        String storage,
        String relativePath,
        String fileName,
        String format,
        String apiVersion
) {

    public CameraMediaReference {
        storage = requireToken(storage, "storage");
        relativePath = requireRelativePath(relativePath);
        fileName = requireToken(fileName, "fileName");
        String lastSegment = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        if (!lastSegment.equals(fileName)) {
            throw new IllegalArgumentException(
                    "fileName must match the media path's last segment");
        }
        format = normalizeFormat(format, fileName);
        apiVersion = requireToken(apiVersion, "apiVersion").toLowerCase(Locale.ROOT);
    }

    private static String requireRelativePath(String value) {
        Objects.requireNonNull(value, "relativePath");
        String path = value.trim();
        URI uri;
        try {
            uri = URI.create(path);
        } catch (IllegalArgumentException exception) {
            throw invalidPath();
        }
        String decodedPath = uri.getPath();
        boolean dotSegment = decodedPath == null
                || Arrays.stream(decodedPath.split("/", -1))
                .anyMatch(segment -> ".".equals(segment) || "..".equals(segment));
        if (!path.startsWith("/ccapi/")
                || uri.isAbsolute()
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || path.contains("://")
                || decodedPath.indexOf('\\') >= 0
                || dotSegment
                || !decodedPath.equals(uri.normalize().getPath())) {
            throw invalidPath();
        }
        return path;
    }

    private static IllegalArgumentException invalidPath() {
        return new IllegalArgumentException(
                "Camera media reference must contain a normalized relative CCAPI path");
    }

    private static String normalizeFormat(String value, String fileName) {
        if (value != null && !value.isBlank()) {
            return value.trim().toUpperCase(Locale.ROOT);
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? "UNKNOWN"
                : fileName.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private static String requireToken(String value, String field) {
        Objects.requireNonNull(value, field);
        String token = value.trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return token;
    }
}
