package com.photographer.focusstack.cr3.metadata;

/** Metadata extracted from a Canon CR3 file. */
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
    private int bitDepth = 14;
    private String compressorVersion = "";
    private String captureDateTime = "";
    private double focalLengthMm;
    private double fNumber;
    private double exposureTime;
    private int orientation = 1;
    private Double gpsLatitude;
    private Double gpsLongitude;
    private Double gpsAltitude;
    private String gpsTimestamp;
    private double[] whiteBalanceMultipliers;
    private int[] perChannelBlackLevels;
    private CanonMakerNoteData makerNoteData;

    public String cameraModel() { return cameraModel; }
    public void setCameraModel(String cameraModel) { this.cameraModel = cameraModel; }
    public String cameraMake() { return cameraMake; }
    public void setCameraMake(String cameraMake) { this.cameraMake = cameraMake; }
    public int sensorWidth() { return sensorWidth; }
    public void setSensorWidth(int sensorWidth) { this.sensorWidth = sensorWidth; }
    public int sensorHeight() { return sensorHeight; }
    public void setSensorHeight(int sensorHeight) { this.sensorHeight = sensorHeight; }
    public int activeAreaLeft() { return activeAreaLeft; }
    public void setActiveAreaLeft(int activeAreaLeft) { this.activeAreaLeft = activeAreaLeft; }
    public int activeAreaTop() { return activeAreaTop; }
    public void setActiveAreaTop(int activeAreaTop) { this.activeAreaTop = activeAreaTop; }
    public int activeAreaRight() { return activeAreaRight; }
    public void setActiveAreaRight(int activeAreaRight) { this.activeAreaRight = activeAreaRight; }
    public int activeAreaBottom() { return activeAreaBottom; }
    public void setActiveAreaBottom(int activeAreaBottom) { this.activeAreaBottom = activeAreaBottom; }
    public int isoSpeed() { return isoSpeed; }
    public void setIsoSpeed(int isoSpeed) { this.isoSpeed = isoSpeed; }
    public String shutterSpeed() { return shutterSpeed; }
    public void setShutterSpeed(String shutterSpeed) { this.shutterSpeed = shutterSpeed; }
    public String aperture() { return aperture; }
    public void setAperture(String aperture) { this.aperture = aperture; }
    public String focalLength() { return focalLength; }
    public void setFocalLength(String focalLength) { this.focalLength = focalLength; }
    public String dateTaken() { return dateTaken; }
    public void setDateTaken(String dateTaken) { this.dateTaken = dateTaken; }
    public int bitDepth() { return bitDepth; }
    public void setBitDepth(int bitDepth) { this.bitDepth = bitDepth; }
    public String compressorVersion() { return compressorVersion; }
    public void setCompressorVersion(String compressorVersion) { this.compressorVersion = compressorVersion; }
    public String captureDateTime() { return captureDateTime; }
    public void setCaptureDateTime(String captureDateTime) { this.captureDateTime = captureDateTime; }
    public double focalLengthMm() { return focalLengthMm; }
    public void setFocalLengthMm(double focalLengthMm) { this.focalLengthMm = focalLengthMm; }
    public double fNumber() { return fNumber; }
    public void setFNumber(double fNumber) { this.fNumber = fNumber; }
    public double exposureTime() { return exposureTime; }
    public void setExposureTime(double exposureTime) { this.exposureTime = exposureTime; }
    public int orientation() { return orientation; }
    public void setOrientation(int orientation) { this.orientation = orientation; }
    public Double gpsLatitude() { return gpsLatitude; }
    public void setGpsLatitude(Double gpsLatitude) { this.gpsLatitude = gpsLatitude; }
    public Double gpsLongitude() { return gpsLongitude; }
    public void setGpsLongitude(Double gpsLongitude) { this.gpsLongitude = gpsLongitude; }
    public Double gpsAltitude() { return gpsAltitude; }
    public void setGpsAltitude(Double gpsAltitude) { this.gpsAltitude = gpsAltitude; }
    public String gpsTimestamp() { return gpsTimestamp; }
    public void setGpsTimestamp(String gpsTimestamp) { this.gpsTimestamp = gpsTimestamp; }
    public double[] whiteBalanceMultipliers() { return whiteBalanceMultipliers; }
    public void setWhiteBalanceMultipliers(double[] multipliers) { this.whiteBalanceMultipliers = multipliers; }
    public int[] perChannelBlackLevels() { return perChannelBlackLevels; }
    public void setPerChannelBlackLevels(int[] levels) { this.perChannelBlackLevels = levels; }
    public CanonMakerNoteData makerNoteData() { return makerNoteData; }
    public void setMakerNoteData(CanonMakerNoteData data) { this.makerNoteData = data; }

    public int visibleWidth() {
        if (activeAreaRight > activeAreaLeft) {
            return activeAreaRight - activeAreaLeft;
        }
        return sensorWidth;
    }

    public int visibleHeight() {
        if (activeAreaBottom > activeAreaTop) {
            return activeAreaBottom - activeAreaTop;
        }
        return sensorHeight;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Cr3Metadata[model=%s, sensor=%dx%d, active=[%d,%d,%d,%d], ISO=%d, %s %s %s, orient=%d"
                .formatted(cameraModel, sensorWidth, sensorHeight,
                        activeAreaLeft, activeAreaTop, activeAreaRight, activeAreaBottom,
                        isoSpeed, shutterSpeed, aperture, focalLength, orientation));
        if (gpsLatitude != null && gpsLongitude != null) {
            sb.append(", GPS=%.6f,%.6f".formatted(gpsLatitude, gpsLongitude));
        }
        if (whiteBalanceMultipliers != null) {
            sb.append(", WB=[%.2f,%.2f,%.2f]".formatted(
                    whiteBalanceMultipliers[0], whiteBalanceMultipliers[1], whiteBalanceMultipliers[2]));
        }
        sb.append(']');
        return sb.toString();
    }
}
