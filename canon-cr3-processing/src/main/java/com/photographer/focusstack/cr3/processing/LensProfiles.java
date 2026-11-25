package com.photographer.focusstack.cr3.processing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Resolves published optical calibration for verified full-field Canon recordings.
 * Selection uses capture metadata. Similar focal ranges can belong to optically different
 * lenses, so only explicitly supported body and lens identifiers are recognized.
 * The independently licensed calibration files and attribution are under META-INF/lensfun.
 */
public final class LensProfiles {
    private LensProfiles() {}

    /**
     * Looks up PTLens distortion, lateral chromatic scaling and PA vignetting coefficients.
     * Dimensions describe the complete developed active area in sensor orientation, before any
     * output crop or sampling. Unknown numeric metadata can be supplied as zero.
     * Missing aperture disables shading. Missing focus also disables shading when the published
     * coefficients depend on focus. Geometry needs only a known lens and valid focal length.
     *
     * @param cameraModel capture's Canon body model
     * @param lensModel capture's lens model, optional for the fixed-lens compact
     * @param focalMm actual focal length in millimetres
     * @param fNumber actual f-number
     * @param focusMetres focus distance in metres, or positive infinity for infinity focus
     * @param activeWidth width of the full active mosaic, before display orientation
     * @param activeHeight height of the full active mosaic, before display orientation
     * @return empty for unsupported lenses, capture modes or focal lengths
     */
    public static Optional<OpticalCorrection> find(String cameraModel, String lensModel, double focalMm,
            double fNumber, double focusMetres, int activeWidth, int activeHeight) {
        if (cameraModel == null || !Double.isFinite(focalMm) || focalMm <= 0) {
            return Optional.empty();
        }
        Profile profile;
        switch (canonModel(cameraModel)) {
            case "POWERSHOTG5XMARKII" -> {
                if (activeWidth != 5496 || activeHeight != 3668) return Optional.empty();
                profile = Tables.G5;
            }
            case "EOSR" -> {
                if (activeWidth != 6744 || activeHeight != 4500 || lensModel == null) return Optional.empty();
                profile = switch (canonModel(lensModel)) {
                    case "RF24-105MMF4LISUSM" -> Tables.RF_ZOOM;
                    case "RF85MMF2MACROISSTM" -> Tables.RF_MACRO;
                    default -> null;
                };
            }
            default -> { return Optional.empty(); }
        }
        if (profile == null || focalMm < profile.distortion.firstKey() || focalMm > profile.distortion.lastKey()) {
            return Optional.empty();
        }
        return Optional.of(profile.correction(focalMm, fNumber, focusMetres));
    }

    private static String canonModel(String name) {
        String model = name.strip().toUpperCase(Locale.ROOT);
        if (model.startsWith("CANON ")) model = model.substring("CANON ".length());
        return model.replace(" ", "").replace("/", "");
    }

    private static final class Tables {
        static final Profile G5 = load("canon-g5x-mark-ii.tsv");
        static final Profile RF_ZOOM = load("canon-rf24-105-f4l.tsv");
        static final Profile RF_MACRO = load("canon-rf85-f2-macro.tsv");
    }

    private record Terms(double x, double y, double z) {
        static final Terms ZERO = new Terms(0, 0, 0);

        Terms mix(Terms other, double fraction) {
            return new Terms(x + fraction * (other.x - x), y + fraction * (other.y - y),
                    z + fraction * (other.z - z));
        }
    }

    private static final class Profile {
        final NavigableMap<Double, Terms> distortion = new TreeMap<>();
        final NavigableMap<Double, Terms> tca = new TreeMap<>();
        // Each focus plane has its own focal-length knots, and each focal length its own apertures.
        final NavigableMap<Double, NavigableMap<Double, NavigableMap<Double, Terms>>> shading = new TreeMap<>();
        boolean shadingIndependentOfFocus;

        OpticalCorrection correction(double focal, double aperture, double distance) {
            Terms d = interpolate(distortion, focal, Function.identity());
            Terms c = interpolate(tca, focal, Function.identity());
            Terms v = Terms.ZERO;
            if (Double.isFinite(aperture) && aperture > 0 && (distance > 0 || shadingIndependentOfFocus)) {
                double dioptres = distance > 0 ? 1 / distance : shading.firstKey();
                double apertureStops = Math.log(aperture);
                v = interpolate(shading, dioptres, plane -> interpolate(plane, focal,
                        apertures -> interpolate(apertures, apertureStops, Function.identity())));
            }
            return new OpticalCorrection(d.x, d.y, d.z, c.x, c.y, v.x, v.y, v.z);
        }
    }

    /**
     * Reproduces each knot exactly. Between samples use linear focal length, log f-number
     * (proportional to aperture stops) and reciprocal focus distance (dioptres). At the edge of
     * a calibration plane retain its nearest measured coefficients. In particular this avoids
     * extrapolating a PA polynomial into the compact camera's unmeasured near-focus range.
     */
    private static <T> Terms interpolate(NavigableMap<Double, T> samples, double x, Function<T, Terms> evaluate) {
        var low = samples.floorEntry(x);
        var high = samples.ceilingEntry(x);
        if (low == null) return evaluate.apply(high.getValue());
        if (high == null || low.getKey().equals(high.getKey())) return evaluate.apply(low.getValue());
        double fraction = (x - low.getKey()) / (high.getKey() - low.getKey());
        return evaluate.apply(low.getValue()).mix(evaluate.apply(high.getValue()), fraction);
    }

    private static Profile load(String name) {
        var stream = LensProfiles.class.getResourceAsStream("/META-INF/lensfun/" + name);
        if (stream == null) throw new IllegalStateException("Missing optical calibration resource " + name);
        var profile = new Profile();
        try (var input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) continue;
                String[] fields = line.split("\t");
                double focal = Double.parseDouble(fields[1]);
                switch (fields[0]) {
                    case "ptlens" -> profile.distortion.put(focal, terms(fields, 2));
                    case "poly3" -> profile.tca.put(focal,
                            new Terms(Double.parseDouble(fields[2]), Double.parseDouble(fields[3]), 0));
                    case "pa" -> {
                        double aperture = Double.parseDouble(fields[2]);
                        double distance = Double.parseDouble(fields[3]);
                        profile.shading.computeIfAbsent(1 / distance, ignored -> new TreeMap<>())
                                .computeIfAbsent(focal, ignored -> new TreeMap<>())
                                .put(Math.log(aperture), terms(fields, 4));
                    }
                    default -> throw new IOException("Unknown calibration model " + fields[0]);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid optical calibration resource " + name, e);
        }
        var firstPlane = profile.shading.firstEntry().getValue();
        profile.shadingIndependentOfFocus = profile.shading.values().stream().allMatch(firstPlane::equals);
        return profile;
    }

    private static Terms terms(String[] fields, int index) {
        return new Terms(Double.parseDouble(fields[index]), Double.parseDouble(fields[index + 1]),
                Double.parseDouble(fields[index + 2]));
    }
}
