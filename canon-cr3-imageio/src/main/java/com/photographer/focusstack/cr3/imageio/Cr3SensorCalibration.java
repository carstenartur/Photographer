package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.metadata.Cr3ColorData;

/**
 * Geometry of the full RAW track and per-exposure calibration recorded by the camera.
 * These fields describe the native mosaic and accompany the sensor raster independently
 * of the defaults selected for RGB development.
 *
 * @param compression full mosaic dimensions, sample depth and CFA arrangement from CMP1
 * @param geometry sensor geometry from IAD1, or {@code null} when unavailable
 * @param color recorded CTMD ColorData, or {@code null} when unavailable
 */
public record Cr3SensorCalibration(Cmp1Box compression, Iad1Box geometry, ColorData color) {

    /**
     * Canon ColorData values in RGGB metadata order. Gain fields are the recorded
     * integer values. Black and white levels are in native sensor sample units.
     *
     * @param redGain red white-balance gain
     * @param greenRedGain green gain on red rows
     * @param greenBlueGain green gain on blue rows
     * @param blueGain blue white-balance gain
     * @param redBlack red black level
     * @param greenRedBlack green black level on red rows
     * @param greenBlueBlack green black level on blue rows
     * @param blueBlack blue black level
     * @param white white level
     */
    public record ColorData(int redGain, int greenRedGain, int greenBlueGain, int blueGain,
                            int redBlack, int greenRedBlack, int greenBlueBlack, int blueBlack, int white) {
        private static ColorData from(Cr3ColorData data) {
            return data == null ? null : new ColorData(data.redGain(), data.greenRedGain(),
                    data.greenBlueGain(), data.blueGain(), data.redBlack(), data.greenRedBlack(),
                    data.greenBlueBlack(), data.blueBlack(), data.white());
        }
    }

    static Cr3SensorCalibration from(Cr3Container container, Cr3ColorData color) {
        for (Cr3Track track : container.tracks()) {
            if (track.type() == Cr3Track.TrackType.CRX_BIG || track.type() == Cr3Track.TrackType.CRX_RAW) {
                return track.cmp1() == null ? null
                        : new Cr3SensorCalibration(track.cmp1(), track.iad1(), ColorData.from(color));
            }
        }
        return null;
    }
}
