package com.photographer.ptp.canon;

import java.util.Map;

/**
 * Decodes Canon EOS property values to human-readable strings.
 * Values documented by Magic Lantern community and miniPtp project.
 */
public class CanonPropertyDecoder {
    private CanonPropertyDecoder() {}

    private static final Map<Integer, String> APERTURE_VALUES = Map.ofEntries(
            Map.entry(0x0D, "f/1.0"), Map.entry(0x10, "f/1.2"), Map.entry(0x13, "f/1.4"),
            Map.entry(0x15, "f/1.6"), Map.entry(0x18, "f/1.8"), Map.entry(0x1B, "f/2.0"),
            Map.entry(0x1D, "f/2.2"), Map.entry(0x20, "f/2.5"), Map.entry(0x23, "f/2.8"),
            Map.entry(0x25, "f/3.2"), Map.entry(0x28, "f/3.5"), Map.entry(0x2B, "f/4.0"),
            Map.entry(0x2D, "f/4.5"), Map.entry(0x30, "f/5.0"), Map.entry(0x33, "f/5.6"),
            Map.entry(0x35, "f/6.3"), Map.entry(0x38, "f/7.1"), Map.entry(0x3B, "f/8.0"),
            Map.entry(0x3D, "f/9.0"), Map.entry(0x40, "f/10"), Map.entry(0x43, "f/11"),
            Map.entry(0x45, "f/13"), Map.entry(0x48, "f/14"), Map.entry(0x4B, "f/16"),
            Map.entry(0x4D, "f/18"), Map.entry(0x50, "f/20"), Map.entry(0x53, "f/22"),
            Map.entry(0x55, "f/25"), Map.entry(0x58, "f/29"), Map.entry(0x5B, "f/32")
    );

    private static final Map<Integer, String> SHUTTER_SPEED_VALUES = Map.ofEntries(
            Map.entry(0x0C, "Bulb"), Map.entry(0x10, "30\""), Map.entry(0x13, "25\""),
            Map.entry(0x15, "20\""), Map.entry(0x18, "15\""), Map.entry(0x1B, "13\""),
            Map.entry(0x1D, "10\""), Map.entry(0x20, "8\""), Map.entry(0x23, "6\""),
            Map.entry(0x25, "5\""), Map.entry(0x28, "4\""), Map.entry(0x2B, "3.2\""),
            Map.entry(0x2D, "2.5\""), Map.entry(0x30, "2\""), Map.entry(0x33, "1.6\""),
            Map.entry(0x35, "1.3\""), Map.entry(0x38, "1\""), Map.entry(0x3B, "0.8\""),
            Map.entry(0x3D, "0.6\""), Map.entry(0x40, "0.5\""), Map.entry(0x43, "0.4\""),
            Map.entry(0x45, "0.3\""), Map.entry(0x48, "1/4"), Map.entry(0x4B, "1/5"),
            Map.entry(0x4D, "1/6"), Map.entry(0x50, "1/8"), Map.entry(0x53, "1/10"),
            Map.entry(0x55, "1/13"), Map.entry(0x58, "1/15"), Map.entry(0x5B, "1/20"),
            Map.entry(0x5D, "1/25"), Map.entry(0x60, "1/30"), Map.entry(0x63, "1/40"),
            Map.entry(0x65, "1/50"), Map.entry(0x68, "1/60"), Map.entry(0x6B, "1/80"),
            Map.entry(0x6D, "1/100"), Map.entry(0x70, "1/125"), Map.entry(0x73, "1/160"),
            Map.entry(0x75, "1/200"), Map.entry(0x78, "1/250"), Map.entry(0x7B, "1/320"),
            Map.entry(0x7D, "1/400"), Map.entry(0x80, "1/500"), Map.entry(0x83, "1/640"),
            Map.entry(0x85, "1/800"), Map.entry(0x88, "1/1000"), Map.entry(0x8B, "1/1250"),
            Map.entry(0x8D, "1/1600"), Map.entry(0x90, "1/2000"), Map.entry(0x93, "1/2500"),
            Map.entry(0x95, "1/3200"), Map.entry(0x98, "1/4000")
    );

    private static final Map<Integer, String> ISO_VALUES = Map.ofEntries(
            Map.entry(0x00, "Auto"), Map.entry(0x28, "6"), Map.entry(0x30, "12"),
            Map.entry(0x38, "25"), Map.entry(0x40, "50"), Map.entry(0x48, "100"),
            Map.entry(0x4B, "125"), Map.entry(0x4D, "160"), Map.entry(0x50, "200"),
            Map.entry(0x53, "250"), Map.entry(0x55, "320"), Map.entry(0x58, "400"),
            Map.entry(0x5B, "500"), Map.entry(0x5D, "640"), Map.entry(0x60, "800"),
            Map.entry(0x63, "1000"), Map.entry(0x65, "1250"), Map.entry(0x68, "1600"),
            Map.entry(0x6B, "2000"), Map.entry(0x6D, "2500"), Map.entry(0x70, "3200"),
            Map.entry(0x73, "4000"), Map.entry(0x75, "5000"), Map.entry(0x78, "6400"),
            Map.entry(0x7B, "8000"), Map.entry(0x7D, "10000"), Map.entry(0x80, "12800"),
            Map.entry(0x83, "16000"), Map.entry(0x85, "20000"), Map.entry(0x88, "25600"),
            Map.entry(0x8B, "32000"), Map.entry(0x8D, "40000"), Map.entry(0x90, "51200")
    );

    public static String decodeAperture(int value) {
        return APERTURE_VALUES.getOrDefault(value, "Unknown(0x%02X)".formatted(value));
    }

    public static String decodeShutterSpeed(int value) {
        return SHUTTER_SPEED_VALUES.getOrDefault(value, "Unknown(0x%02X)".formatted(value));
    }

    public static String decodeIso(int value) {
        return ISO_VALUES.getOrDefault(value, "Unknown(0x%02X)".formatted(value));
    }

    public static int decodeBatteryLevel(int value) {
        return switch (value) {
            case 0 -> 0;
            case 1 -> 25;
            case 2 -> 50;
            case 3 -> 75;
            case 4 -> 100;
            default -> value;
        };
    }
}
