package com.photographer.focusstack.common.media;

/** Coarse classification of a media item; used by ingest policies. */
public enum MediaType {
    JPEG,
    HEIF,
    RAW,
    VIDEO,
    OTHER;

    /** Best-effort classification from a filename. */
    public static MediaType fromFilename(String filename) {
        if (filename == null) return OTHER;
        String lower = filename.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return OTHER;
        String ext = lower.substring(dot + 1);
        return switch (ext) {
            case "jpg", "jpeg" -> JPEG;
            case "heif", "heic" -> HEIF;
            case "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "dng", "pef", "srw" -> RAW;
            case "mov", "mp4", "m4v", "avi", "mkv" -> VIDEO;
            default -> OTHER;
        };
    }
}
