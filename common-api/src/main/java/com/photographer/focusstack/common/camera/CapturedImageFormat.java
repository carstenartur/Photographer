package com.photographer.focusstack.common.camera;

import java.time.Duration;
import java.util.Locale;

/** Format preference for the original image supplied to photo processing. */
public final class CapturedImageFormat {

    public static final Duration PAIRED_FILE_WAIT = Duration.ofMillis(500);

    private CapturedImageFormat() {
    }

    public static boolean isCr3(String name) {
        return extension(name).equals("CR3");
    }

    public static boolean hasCr3Header(byte[] data) {
        return data != null && data.length >= 12
                && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p'
                && data[8] == 'c' && data[9] == 'r' && data[10] == 'x' && data[11] == ' ';
    }

    public static boolean isCanonRaw(String name) {
        String extension = extension(name);
        return extension.equals("CR3") || extension.equals("CR2");
    }

    /** CR3 is currently the Canon RAW format supported by the processing pipeline. */
    public static int processingPriority(String name) {
        return switch (extension(name)) {
            case "CR3" -> 2;
            case "JPG", "JPEG", "PNG", "BMP" -> 1;
            default -> 0;
        };
    }

    public static boolean canHavePairedFile(String name) {
        return switch (extension(name)) {
            case "CR3", "CR2", "JPG", "JPEG" -> true;
            default -> false;
        };
    }

    /** Keeps the containing directory in the key so identical names on other cards stay distinct. */
    public static String shotKey(String path) {
        return canHavePairedFile(path) ? path.substring(0, path.lastIndexOf('.') + 1) : path;
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }
}
