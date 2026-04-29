package com.photographer.focusstack.ai.permission;

/**
 * Defines which parts of an image or session may be sent to the AI provider.
 *
 * <p>This scope controls <em>what data</em> is transmitted rather than
 * <em>what actions</em> are permitted (which is governed by {@link AIAccessLevel}).
 * Combining both gives full GDPR-friendly control over AI data processing.</p>
 */
public enum AIPrivacyScope {

    /**
     * Only non-visual metadata (EXIF, timestamps, camera settings) may be sent.
     * No pixel data leaves the application.
     */
    METADATA_ONLY,

    /**
     * Downscaled thumbnail images (≤ 256 × 256 px) may be sent in addition
     * to metadata. Full-resolution images are never transmitted.
     */
    THUMBNAILS,

    /**
     * Full-resolution images may be sent to the AI provider together with
     * all associated metadata.
     */
    FULL_IMAGES;

    /**
     * Returns {@code true} when this scope permits at least the data access
     * required by {@code required}.
     *
     * @param required the minimum required privacy scope
     * @return {@code true} iff {@code this.ordinal() >= required.ordinal()}
     */
    public boolean allows(AIPrivacyScope required) {
        return this.ordinal() >= required.ordinal();
    }
}
