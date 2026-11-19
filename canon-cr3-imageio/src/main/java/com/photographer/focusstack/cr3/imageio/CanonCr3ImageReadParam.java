package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;

import javax.imageio.ImageReadParam;
import java.util.Objects;

/**
 * Extended read parameters for Canon CR3 images.
 *
 * <p>Extends {@link ImageReadParam} with CR3-specific options like output mode
 * and processing pipeline control. The default develops a full-resolution RAW
 * color image. Use {@link #setPreviewOnly(boolean) setPreviewOnly(true)} for the
 * camera's embedded JPEG.</p>
 *
 * <p><strong>API status:</strong> preview selection is supported for external
 * consumers. Full-RAW and processing controls are experimental while the CRX
 * decoder is being completed and may evolve between pre-1.0 releases.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
 * param.setPreviewOnly(true);
 * ImageReader reader = ...;
 * BufferedImage image = reader.read(0, param);
 * }</pre>
 */
public class CanonCr3ImageReadParam extends ImageReadParam {

    private Cr3DecodeOptions decodeOptions = Cr3DecodeOptions.rawProcessed();
    private Cr3DecodeOptions previewOptions;
    private boolean explicitDecodeOptions;
    private boolean useDestinationAsWorkspace;

    /**
     * Returns the CR3-specific decode options.
     *
     * <p>Options that select full RAW decoding or experimental processing are not
     * yet stable public API.</p>
     *
     * <p>The returned options are mutable. A change to their output mode, depth or
     * encoding is preserved when a destination is selected. With default eight-bit
     * sRGB output, a compatible sixteen-bit RGB destination can select its depth
     * and encoding without mutating these options.</p>
     *
     * @return the decode options
     */
    public Cr3DecodeOptions getDecodeOptions() {
        return previewOptions != null ? previewOptions : decodeOptions;
    }

    /**
     * Sets the CR3-specific decode options.
     *
     * <p>Preview mode is supported. Full RAW and experimental processing options
     * follow the maturity of the underlying CRX decoder and may change before 1.0.</p>
     *
     * <p>Explicit options take precedence over destination-based RGB depth and encoding
     * selection. An incompatible destination is rejected rather than changing these options.</p>
     *
     * @param options the decode options (must not be null)
     * @throws NullPointerException if options is null
     */
    public void setDecodeOptions(Cr3DecodeOptions options) {
        this.decodeOptions = Objects.requireNonNull(options, "Decode options must not be null");
        explicitDecodeOptions = true;
        if (isPreviewOnly()) {
            previewOptions = options.copy().outputMode(Cr3DecodeOptions.OutputMode.PREVIEW);
        }
    }

    boolean hasExplicitDecodeOptions() {
        return explicitDecodeOptions;
    }

    /**
     * Indicates whether a supplied RGB destination may serve as RAW working storage.
     *
     * @return whether in-place development was requested
     */
    public boolean usesDestinationAsWorkspace() {
        return useDestinationAsWorkspace;
    }

    /**
     * Allows compatible, caller-owned full-frame 8-bit RGB destinations to be used as
     * RAW working storage. Development can overwrite the previous contents even
     * if the read fails. Use this for a buffer reserved for image processing.
     *
     * @param enabled whether to allow in-place development
     */
    public void setUseDestinationAsWorkspace(boolean enabled) {
        useDestinationAsWorkspace = enabled;
    }

    /**
     * Returns whether only the embedded JPEG preview should be read.
     *
     * <p>When {@code true}, the reader will extract the embedded JPEG preview
     * image instead of decoding the full RAW data. This is significantly faster
     * and is useful for thumbnailing or quick previews.</p>
     *
     * @return {@code true} if only the preview should be read
     */
    public boolean isPreviewOnly() {
        return previewOptions != null;
    }

    /**
     * Sets whether only the embedded JPEG preview should be read.
     *
     * <p>While enabled, the effective options are an independent preview copy.
     * Disabling preview restores the latest options supplied by the caller.</p>
     *
     * @param previewOnly {@code true} to read only the preview
     */
    public void setPreviewOnly(boolean previewOnly) {
        if (previewOnly != isPreviewOnly()) {
            previewOptions = previewOnly ? decodeOptions.copy().outputMode(Cr3DecodeOptions.OutputMode.PREVIEW) : null;
        }
    }
}
