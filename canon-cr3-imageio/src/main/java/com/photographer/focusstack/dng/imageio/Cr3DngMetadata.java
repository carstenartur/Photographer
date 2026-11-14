package com.photographer.focusstack.dng.imageio;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.imageio.Cr3ImageMetadata;
import com.photographer.focusstack.cr3.imageio.Cr3SensorCalibration.ColorData;
import java.awt.Rectangle;
import java.util.Map;
import javax.imageio.IIOException;
import javax.imageio.plugins.tiff.TIFFDirectory;

/** Maps the original full sensor geometry and per-exposure calibration to DNG 1.1. */
final class Cr3DngMetadata {

    private Cr3DngMetadata() {}

    static TIFFDirectory fromCr3(Cr3ImageMetadata metadata) throws IIOException {
        var camera = metadata.getCr3Metadata();
        var sensor = metadata.sensorCalibration();
        if (sensor == null || sensor.color() == null) {
            throw new IIOException("CR3 export requires the full RAW track and its measured CTMD ColorData");
        }
        Profile profile = PROFILES.get(camera.cameraModel());
        if (profile == null) throw new IIOException("No DNG color profile for " + camera.cameraModel());
        Cmp1Box cmp = sensor.compression();
        Iad1Box iad = sensor.geometry();
        if (iad == null || !iad.isBigImage() || cmp.cfaLayout() < 0 || cmp.cfaLayout() > 3
                || cmp.bitsPerSample() < 1 || cmp.bitsPerSample() > 16 || cmp.numPlanes() != 4) {
            throw new IIOException("Unsupported CR3 sensor geometry or Bayer pattern");
        }
        Rectangle active = rectangle(iad.activeLeft(), iad.activeTop(), iad.activeRight(), iad.activeBottom(),
                cmp.imageWidth(), cmp.imageHeight());
        Rectangle crop = rectangle(iad.cropLeft(), iad.cropTop(), iad.cropRight(), iad.cropBottom(),
                cmp.imageWidth(), cmp.imageHeight());
        if (!active.contains(crop)) throw new IIOException("CR3 default crop exceeds its active area");

        ColorData color = sensor.color();
        int[] black = {color.redBlack(), color.greenRedBlack(), color.greenBlueBlack(), color.blueBlack()};
        int[] pattern = new int[4];
        int[] levels = new int[4];
        int[] planeColor = {0, 1, 1, 2};
        // CMP1 CFA layout is the phase of RGGB at the full-sensor origin; DNG arrays
        // start at the active-area origin. Both the colour and black level use that phase.
        int phase = cmp.cfaLayout() ^ (active.x & 1) ^ ((active.y & 1) << 1);
        for (int i = 0; i < 4; i++) {
            pattern[i] = planeColor[i ^ phase];
            levels[i] = black[i ^ phase];
            if (levels[i] < 0 || levels[i] >= color.white()) throw new IIOException("Invalid CR3 black/white levels");
        }
        if (color.white() > (1 << cmp.bitsPerSample()) - 1
                || color.redGain() <= 0 || color.greenRedGain() <= 0 || color.greenBlueGain() <= 0 || color.blueGain() <= 0) {
            throw new IIOException("Invalid CR3 sensor white level or white balance");
        }
        TIFFDirectory d = DngTags.bayerDirectory(cmp.imageWidth(), cmp.imageHeight(), camera.cameraModel(), pattern, color.white());
        d.addTIFFField(DngTags.ascii(271, camera.cameraMake()));
        d.addTIFFField(DngTags.ascii(272, camera.cameraModel()));
        d.addTIFFField(DngTags.shorts(274, camera.orientation()));
        if (camera.dateTaken() != null && !camera.dateTaken().isBlank()) d.addTIFFField(DngTags.ascii(306, camera.dateTaken()));
        d.addTIFFField(DngTags.longs(50829, active.y, active.x, active.y + active.height, active.x + active.width));
        d.addTIFFField(DngTags.longs(50719, crop.x - active.x, crop.y - active.y));
        d.addTIFFField(DngTags.longs(50720, crop.width, crop.height));
        d.addTIFFField(DngTags.shorts(50713, 2, 2));
        d.addTIFFField(DngTags.shorts(50714, levels));
        long green = (long) color.greenRedGain() + color.greenBlueGain();
        d.addTIFFField(DngTags.rationals(50728, new long[][]{
                {green, 2L * color.redGain()}, {1, 1}, {green, 2L * color.blueGain()}}));
        d.addTIFFField(DngTags.shorts(50778, 17)); // Standard Light A
        d.addTIFFField(DngTags.signedRationals(50721, coefficients(profile.tungsten())));
        d.addTIFFField(DngTags.shorts(50779, profile.daylightIlluminant()));
        d.addTIFFField(DngTags.signedRationals(50722, coefficients(profile.daylight())));
        return d;
    }

    private static Rectangle rectangle(int left, int top, int right, int bottom, int width, int height) throws IIOException {
        // IAD1 stores the last pixel inclusively; DNG uses the first pixel outside.
        if (left < 0 || top < 0 || right < left || bottom < top || right >= width || bottom >= height) {
            throw new IIOException("CR3 image area is outside the full sensor");
        }
        return new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    private static int[][] coefficients(int[] numerators) {
        int[][] matrix = new int[9][2];
        for (int i = 0; i < matrix.length; i++) {
            matrix[i][0] = numerators[i];
            matrix[i][1] = 10_000;
        }
        return matrix;
    }

    /** ColorMatrix1 and ColorMatrix2 map XYZ to the native camera channels for their stated illuminants. */
    private record Profile(int[] tungsten, int daylightIlluminant, int[] daylight) {}

    // RawTherapee 5.12, rtdata/dcpprofiles. The ProfileCopyright field of each
    // source DCP reads "public domain" (EOS RP: "RawTherapee CC0").
    // https://github.com/RawTherapee/RawTherapee/tree/5.12/rtdata/dcpprofiles
    private static final Map<String, Profile> PROFILES = Map.of(
            "Canon PowerShot G5 X Mark II", new Profile(
                    new int[]{13947, -7176, 1142, -2899, 12760, 3860, -803, 1528, 7552}, 21,
                    new int[]{8310, -2823, -822, -3655, 11640, 2393, -1073, 1980, 5651}),
            "Canon EOS R", new Profile(
                    new int[]{11389, -3758, 555, -7543, 17391, 5143, -1996, 3429, 9523}, 21,
                    new int[]{5684, -107, -624, -4777, 12042, 3006, -1679, 3274, 6353}),
            "Canon EOS RP", new Profile(
                    new int[]{11161, -3052, 9, -5323, 15148, 4610, -791, 1974, 7720}, 23,
                    new int[]{6057, -736, -716, -4898, 12192, 3076, -1102, 2159, 6897}),
            "Canon EOS M50", new Profile(
                    new int[]{13140, -3405, -93, -4904, 14328, 4267, 468, 1165, 9533}, 21,
                    new int[]{7284, -62, -844, -4644, 11854, 3063, -1694, 3436, 7293}));
}
