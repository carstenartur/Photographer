package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.encode.Cr3CameraProfile;
import javax.imageio.ImageWriteParam;
import java.util.Objects;

/** Parameters for writing a full native mosaic with a selected CR3 camera profile. */
public final class CanonCr3ImageWriteParam extends ImageWriteParam {
    private Cr3CameraProfile cameraProfile;

    /** Creates parameters with the default locale and no per-image profile. */
    public CanonCr3ImageWriteParam() {
        super(null);
    }

    /** Selects the lossless CR3 camera profile for this image. */
    public void setCameraProfile(Cr3CameraProfile profile) {
        cameraProfile = Objects.requireNonNull(profile, "profile");
    }

    /** Returns the per-image camera profile, or null to use the writer's default. */
    public Cr3CameraProfile getCameraProfile() {
        return cameraProfile;
    }
}
