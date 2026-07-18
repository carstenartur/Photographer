package com.photographer.focusstack.cr3.reference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Canonical fixture-to-reference sidecar path mapping. */
public final class ReferencePaths {

    private ReferencePaths() {
    }

    /** Returns the RAW-linear TIFF sidecar path for a CR3 fixture. */
    public static Path rawLinearTiff(Path fixture) {
        return sidecar(fixture, ".reference.tiff");
    }

    /** Returns the rendered preview PNG sidecar path for a CR3 fixture. */
    public static Path renderedPreviewPng(Path fixture) {
        return sidecar(fixture, ".reference.png");
    }

    /** Returns whether the fixture has a RAW-linear TIFF sidecar. */
    public static boolean hasRawLinearTiff(Path fixture) {
        return Files.isRegularFile(rawLinearTiff(fixture));
    }

    /** Returns whether both canonical reference sidecars exist. */
    public static boolean hasBothSidecars(Path fixture) {
        return hasRawLinearTiff(fixture) && Files.isRegularFile(renderedPreviewPng(fixture));
    }

    private static Path sidecar(Path fixture, String suffix) {
        Objects.requireNonNull(fixture, "fixture");
        String filename = fixture.getFileName().toString();
        int extension = filename.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".cr3");
        String stem = extension >= 0 && extension == filename.length() - 4
                ? filename.substring(0, extension)
                : filename;
        return fixture.resolveSibling(stem + suffix);
    }
}
