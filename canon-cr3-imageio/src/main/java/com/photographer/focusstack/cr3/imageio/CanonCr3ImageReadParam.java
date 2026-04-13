package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;

import javax.imageio.ImageReadParam;
import java.util.Objects;

/**
 * Extended read parameters for Canon CR3 images.
 *
 * <p>Extends {@link ImageReadParam} with CR3-specific options like output mode
 * (preview vs. full RAW) and processing pipeline control.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
 * param.setDecodeOptions(Cr3DecodeOptions.rawProcessed());
 * ImageReader reader = ...;
 * BufferedImage image = reader.read(0, param);
 * }</pre>
 */
public class CanonCr3ImageReadParam extends ImageReadParam {

    private Cr3DecodeOptions decodeOptions = Cr3DecodeOptions.defaults();
    private boolean previewOnly;

    /**
     * Returns the CR3-specific decode options.
     *
     * @return the decode options
     */
    public Cr3DecodeOptions getDecodeOptions() {
        return decodeOptions;
    }

    /**
     * Sets the CR3-specific decode options.
     *
     * @param options the decode options (must not be null)
     * @throws NullPointerException if options is null
     */
    public void setDecodeOptions(Cr3DecodeOptions options) {
        this.decodeOptions = Objects.requireNonNull(options, "Decode options must not be null");
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
