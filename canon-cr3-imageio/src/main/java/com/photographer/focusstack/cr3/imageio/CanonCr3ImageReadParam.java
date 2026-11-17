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
    private boolean previewOnly;
    private boolean useDestinationAsWorkspace;

    /**
     * Returns the CR3-specific decode options.
     *
     * <p>Options that select full RAW decoding or experimental processing are not
     * yet stable public API.</p>
     *
     * @return the decode options
     */
    public Cr3DecodeOptions getDecodeOptions() {
        return decodeOptions;
    }

    /**
     * Sets the CR3-specific decode options.
     *
     * <p>Preview mode is supported. Full RAW and experimental processing options
     * follow the maturity of the underlying CRX decoder and may change before 1.0.</p>
     *
     * @param options the decode options (must not be null)
     * @throws NullPointerException if options is null
     */
    public void setDecodeOptions(Cr3DecodeOptions options) {
        this.decodeOptions = Objects.requireNonNull(options, "Decode options must not be null");
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
     * Allows compatible, caller-owned full-frame RGB destinations to be used as
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
        return previewOnly;
    }

    /**
     * Sets whether only the embedded JPEG preview should be read.
     *
     * <p>When set to {@code true}, the decode options are automatically
     * configured to use {@link Cr3DecodeOptions.OutputMode#PREVIEW}.</p>
     *
     * @param previewOnly {@code true} to read only the preview
     */
    public void setPreviewOnly(boolean previewOnly) {
        this.previewOnly = previewOnly;
        if (previewOnly) {
            this.decodeOptions = this.decodeOptions.outputMode(Cr3DecodeOptions.OutputMode.PREVIEW);
        }
    }
}
