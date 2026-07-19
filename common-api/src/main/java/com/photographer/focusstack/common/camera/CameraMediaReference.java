package com.photographer.focusstack.common.camera;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Vendor-neutral reference to one camera media object.
 *
 * <p>The reference contains a relative camera API path only. Absolute transport
 * URLs, camera addresses and authentication material are deliberately excluded.</p>
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
            throw new IllegalArgumentException("fileName must match the media path's last segment");
        }
        format = normalizeFormat(format, fileName);
        apiVersion = requireToken(apiVersion, "apiVersion").toLowerCase(Locale.ROOT);
    }

    private static String requireRelativePath(String value) {
        Objects.requireNonNull(value, "relativePath");
        String path = value.trim();
        boolean parentSegment = Arrays.stream(path.split("/", -1)).anyMatch(".."::equals);
        if (!path.startsWith("/ccapi/")
                || path.contains("://")
                || path.contains("?")
                || path.contains("#")
                || parentSegment) {
            throw new IllegalArgumentException(
                    "Camera media reference must contain a normalized relative CCAPI path");
        }
        return path;
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
