package com.photographer.focusstack.cr3.imageio;

import javax.imageio.ImageWriteParam;
import java.nio.file.Path;
import java.util.Objects;

/** Parameters for writing a full native mosaic with a selected CR3 camera reference. */
public final class CanonCr3ImageWriteParam extends ImageWriteParam {
    private Path cameraReference;

    /** Creates parameters with the default locale and no per-image reference. */
    public CanonCr3ImageWriteParam() {
        super(null);
    }

    /** Selects the lossless CR3 file that supplies this image's camera format and capture metadata. */
    public void setCameraReference(Path cameraReference) {
        this.cameraReference = Objects.requireNonNull(cameraReference, "cameraReference");
    }

    /** Returns the per-image camera reference, or null to use the writer's default. */
    public Path getCameraReference() {
        return cameraReference;
    }
}
