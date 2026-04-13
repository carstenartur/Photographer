package com.photographer.focusstack.cr3.metadata;

/**
 * Metadata extracted from a Canon CR3 file.
 *
 * <p>Contains camera model information, sensor geometry, EXIF data references,
 * and Canon-specific MakerNote information.</p>
 *
 * <p>Initially populated from the moov/uuid and CMT boxes. Additional metadata
 * can be extracted from EXIF segments embedded in JPEG previews.</p>
 *
 * TODO: Parse full EXIF from CMT1/CMT2 boxes
 * TODO: Parse Canon MakerNote from CMT3 box
 * TODO: Extract GPS data from CMT4 box
 */
public final class Cr3Metadata {

    private String cameraModel = "";
    private String cameraMake = "";
    private int sensorWidth;
    private int sensorHeight;
    private int activeAreaLeft;
    private int activeAreaTop;
    private int activeAreaRight;
    private int activeAreaBottom;
    private int isoSpeed;
    private String shutterSpeed = "";
    private String aperture = "";
    private String focalLength = "";
    private String dateTaken = "";
    private int bitDepth = 14;  // CR3 typically uses 14-bit sensor data
    private String compressorVersion = "";
    private String captureDateTime = "";
    private double focalLengthMm;
    private double fNumber;
    private double exposureTime;

    /** Returns the camera model name (e.g. "Canon PowerShot G5 X Mark II"). */
    public String cameraModel() { return cameraModel; }

    /** Sets the camera model name. */
    public void setCameraModel(String cameraModel) { this.cameraModel = cameraModel; }

    /** Returns the camera make (e.g. "Canon"). */
    public String cameraMake() { return cameraMake; }

    /** Sets the camera make. */
    public void setCameraMake(String cameraMake) { this.cameraMake = cameraMake; }

    /** Returns the full sensor width in pixels. */
    public int sensorWidth() { return sensorWidth; }

    /** Sets the full sensor width. */
    public void setSensorWidth(int sensorWidth) { this.sensorWidth = sensorWidth; }

    /** Returns the full sensor height in pixels. */
    public int sensorHeight() { return sensorHeight; }

    /** Sets the full sensor height. */
    public void setSensorHeight(int sensorHeight) { this.sensorHeight = sensorHeight; }

    /** Returns the left edge of the active sensor area. */
    public int activeAreaLeft() { return activeAreaLeft; }

    /** Sets the active area left offset. */
    public void setActiveAreaLeft(int activeAreaLeft) { this.activeAreaLeft = activeAreaLeft; }

    /** Returns the top edge of the active sensor area. */
    public int activeAreaTop() { return activeAreaTop; }

    /** Sets the active area top offset. */
    public void setActiveAreaTop(int activeAreaTop) { this.activeAreaTop = activeAreaTop; }

    /** Returns the right edge of the active sensor area. */
    public int activeAreaRight() { return activeAreaRight; }

    /** Sets the active area right edge. */
    public void setActiveAreaRight(int activeAreaRight) { this.activeAreaRight = activeAreaRight; }

    /** Returns the bottom edge of the active sensor area. */
    public int activeAreaBottom() { return activeAreaBottom; }

    /** Sets the active area bottom edge. */
    public void setActiveAreaBottom(int activeAreaBottom) { this.activeAreaBottom = activeAreaBottom; }

    /** Returns the ISO speed. */
    public int isoSpeed() { return isoSpeed; }

    /** Sets the ISO speed. */
    public void setIsoSpeed(int isoSpeed) { this.isoSpeed = isoSpeed; }

    /** Returns the shutter speed as a string (e.g. "1/250"). */
    public String shutterSpeed() { return shutterSpeed; }

    /** Sets the shutter speed string. */
    public void setShutterSpeed(String shutterSpeed) { this.shutterSpeed = shutterSpeed; }

    /** Returns the aperture as a string (e.g. "f/2.8"). */
    public String aperture() { return aperture; }

    /** Sets the aperture string. */
    public void setAperture(String aperture) { this.aperture = aperture; }

    /** Returns the focal length as a string (e.g. "50mm"). */
    public String focalLength() { return focalLength; }

    /** Sets the focal length string. */
    public void setFocalLength(String focalLength) { this.focalLength = focalLength; }

    /** Returns the date the photo was taken. */
    public String dateTaken() { return dateTaken; }

    /** Sets the date taken. */
    public void setDateTaken(String dateTaken) { this.dateTaken = dateTaken; }

    /** Returns the bit depth of the RAW sensor data (typically 14). */
    public int bitDepth() { return bitDepth; }

    /** Sets the bit depth. */
    public void setBitDepth(int bitDepth) { this.bitDepth = bitDepth; }

    /** Returns the Canon compressor version string (e.g. "CanonCR3_001/00.09.00/00.00.00"). */
    public String compressorVersion() { return compressorVersion; }

    /** Sets the compressor version string. */
    public void setCompressorVersion(String compressorVersion) { this.compressorVersion = compressorVersion; }

    /** Returns the capture date/time as a string. */
    public String captureDateTime() { return captureDateTime; }

    /** Sets the capture date/time string. */
    public void setCaptureDateTime(String captureDateTime) { this.captureDateTime = captureDateTime; }

    /** Returns the focal length in millimetres. */
    public double focalLengthMm() { return focalLengthMm; }

    /** Sets the focal length in millimetres. */
    public void setFocalLengthMm(double focalLengthMm) { this.focalLengthMm = focalLengthMm; }

    /** Returns the f-number (aperture). */
    public double fNumber() { return fNumber; }

    /** Sets the f-number. */
    public void setFNumber(double fNumber) { this.fNumber = fNumber; }

    /** Returns the exposure time in seconds. */
    public double exposureTime() { return exposureTime; }

    /** Sets the exposure time in seconds. */
    public void setExposureTime(double exposureTime) { this.exposureTime = exposureTime; }

    /**
     * Returns the visible (cropped) image width based on active area.
     *
     * @return visible width, or sensor width if active area is not set
     */
    public int visibleWidth() {
        if (activeAreaRight > activeAreaLeft) {
            return activeAreaRight - activeAreaLeft;
        }
        return sensorWidth;
    }

    /**
     * Returns the visible (cropped) image height based on active area.
     *
     * @return visible height, or sensor height if active area is not set
     */
    public int visibleHeight() {
        if (activeAreaBottom > activeAreaTop) {
            return activeAreaBottom - activeAreaTop;
        }
        return sensorHeight;
    }

    @Override
    public String toString() {
        return "Cr3Metadata[model=%s, sensor=%dx%d, active=[%d,%d,%d,%d], ISO=%d, %s %s %s]"
                .formatted(cameraModel, sensorWidth, sensorHeight,
                        activeAreaLeft, activeAreaTop, activeAreaRight, activeAreaBottom,
                        isoSpeed, shutterSpeed, aperture, focalLength);
    }
}
