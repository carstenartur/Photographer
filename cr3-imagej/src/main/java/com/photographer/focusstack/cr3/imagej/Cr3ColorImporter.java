package com.photographer.focusstack.cr3.imagej;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReadParam;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReaderSpi;
import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import java.awt.image.BandedSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.stream.FileImageInputStream;

/** Develops supported Canon CR3 RAW data to a three-channel unsigned 16-bit sRGB composite. */
public final class Cr3ColorImporter implements PlugIn {
    private static final String[] CHANNELS = {"Red", "Green", "Blue"};

    @Override
    public void run(String arg) {
        String path = arg;
        if (path == null || path.isEmpty()) {
            path = new OpenDialog("Open CR3 Color Image", null).getPath();
            if (path == null) {
                return;
            }
        }
        try {
            open(Path.of(path)).show();
        } catch (IOException | IllegalArgumentException e) {
            IJ.error("CR3 Color Image", path + "\n" + e.getMessage());
        }
    }

    /**
     * Opens an oriented, active-area RGB image without opening an ImageJ window.
     *
     * @param path Canon CR3 file
     * @return the developed image with unsigned 16-bit red, green and blue channels
     * @throws IOException if the file cannot be read or developed
     */
    public static CompositeImage open(Path path) throws IOException {
        var reader = new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new FileImageInputStream(path.toFile())) {
            reader.setInput(input);
            var param = new CanonCr3ImageReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawProcessed().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN));
            return toComposite(path.getFileName().toString(), reader.read(0, param).getRaster());
        } finally {
            reader.dispose();
        }
    }

    // The importer owns the decoded image, so ImageJ can take ownership of its packed planes.
    static CompositeImage toComposite(String title, Raster source) {
        if (source.getNumBands() != 3 || source.getTransferType() != DataBuffer.TYPE_USHORT) {
            throw new IllegalArgumentException("Expected three unsigned 16-bit RGB bands");
        }
        for (int bits : source.getSampleModel().getSampleSize()) {
            if (bits != 16) {
                throw new IllegalArgumentException("Expected 16 bits in each RGB band");
            }
        }
        int width = source.getWidth();
        int height = source.getHeight();
        short[][] planes = contiguousPlanes(source);
        if (planes == null) {
            planes = new short[3][Math.multiplyExact(width, height)];
            int[] row = new int[width];
            for (int band = 0; band < planes.length; band++) {
                for (int y = 0; y < height; y++) {
                    source.getSamples(source.getMinX(), source.getMinY() + y, width, 1, band, row);
                    for (int x = 0; x < width; x++) {
                        planes[band][y * width + x] = (short) row[x];
                    }
                }
            }
        }

        var stack = new ImageStack(width, height);
        for (int band = 0; band < planes.length; band++) {
            stack.addSlice(CHANNELS[band], planes[band]);
        }
        var base = new ImagePlus(title, stack);
        base.setDimensions(3, 1, 1);
        base.setIgnoreGlobalCalibration(true);
        var image = new CompositeImage(base, CompositeImage.COMPOSITE);
        image.setIgnoreGlobalCalibration(true);
        // Set the LUTs without switching channels, which can render a packed display image.
        for (int channel = 1; channel <= planes.length; channel++) {
            var lut = image.getChannelLut(channel);
            lut.min = 0;
            lut.max = 65535;
        }
        image.setDisplayRange(0, 65535);
        return image;
    }

    private static short[][] contiguousPlanes(Raster source) {
        if (!(source.getSampleModel() instanceof BandedSampleModel layout)
                || !(source.getDataBuffer() instanceof DataBufferUShort buffer)
                || source.getMinX() != source.getSampleModelTranslateX()
                || source.getMinY() != source.getSampleModelTranslateY()
                || layout.getScanlineStride() != source.getWidth()) {
            return null;
        }
        int size = Math.multiplyExact(source.getWidth(), source.getHeight());
        int[] banks = layout.getBankIndices();
        int[] offsets = buffer.getOffsets();
        int[] bandOffsets = layout.getBandOffsets();
        short[][] planes = new short[3][];
        for (int band = 0; band < planes.length; band++) {
            int bank = banks[band];
            planes[band] = buffer.getData(bank);
            if (offsets[bank] != 0 || bandOffsets[band] != 0 || planes[band].length != size) {
                return null;
            }
        }
        if (planes[0] == planes[1] || planes[0] == planes[2] || planes[1] == planes[2]) {
            return null;
        }
        return planes;
    }
}
