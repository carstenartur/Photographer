package com.photographer.focusstack.cr3.imageio;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;

/** Describes the existing native CR3 DOM tree to ImageIO metadata consumers. */
public final class Cr3MetadataFormat extends IIOMetadataFormatImpl {
    private static final Cr3MetadataFormat INSTANCE = new Cr3MetadataFormat();

    private Cr3MetadataFormat() {
        super("CanonCR3Metadata", CHILD_POLICY_SOME);
        element("Camera", "make", "model");
        element("Image", "sensorWidth", "sensorHeight", "visibleWidth", "visibleHeight", "orientation",
                "bitDepth", "bayerPattern", "whiteLevel", "blackLevel");
        element("Crop", "left", "top", "right", "bottom");
        element("WhiteBalance", "r", "g", "b");
        element("GPS", "latitude", "longitude", "altitude");
        element("Capture", "iso", "shutterSpeed", "aperture", "focalLength", "dateTaken");
    }

    private void element(String name, String... attributes) {
        addElement(name, getRootName(), CHILD_POLICY_EMPTY);
        for (String attribute : attributes) {
            addAttribute(name, attribute, DATATYPE_STRING, false, null);
        }
    }

    /**
     * Returns the descriptor used for native CR3 metadata lookup by ImageIO.
     *
     * @return the shared schema for the {@code CanonCR3Metadata} tree
     */
    public static IIOMetadataFormat getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean canNodeAppear(String elementName, ImageTypeSpecifier imageType) {
        getChildPolicy(elementName); // Validate the element name.
        return true;
    }
}
