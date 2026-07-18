package com.photographer.focusstack.cr3.reference;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReferencePathsTest {

    @Test
    void mapsCr3FixtureToCanonicalSidecarsCaseInsensitively() {
        Path fixture = Path.of("samples", "Camera.CR3");

        assertThat(ReferencePaths.rawLinearTiff(fixture))
                .isEqualTo(Path.of("samples", "Camera.reference.tiff"));
        assertThat(ReferencePaths.renderedPreviewPng(fixture))
                .isEqualTo(Path.of("samples", "Camera.reference.png"));
    }
}
