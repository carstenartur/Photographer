package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.metadata.Cr3ColorData;

/** Geometry of the full RAW track and per-exposure calibration recorded by the camera. */
public record Cr3SensorCalibration(Cmp1Box compression, Iad1Box geometry, ColorData color) {

    /** Canon ColorData values, in native sensor units and RGGB channel order. */
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
