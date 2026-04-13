package com.photographer.focusstack.cr3.metadata;

import com.photographer.focusstack.cr3.bmff.CncvBox;
import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * Reads metadata from CR3 files independently of image data decoding.
 */
public final class Cr3MetadataReader {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3MetadataReader.class);

    private Cr3MetadataReader() {}

    public static Cr3Metadata read(ImageInputStream input, Cr3Container container)
            throws IOException {
        Cr3Metadata metadata = new Cr3Metadata();
        metadata.setCameraMake("Canon");

        // Set sensor dimensions and bit depth from RAW track if available
        for (var track : container.tracks()) {
            if (track.type() == Cr3Track.TrackType.CRX_RAW
                    || track.type() == Cr3Track.TrackType.CRX_BIG) {
                metadata.setSensorWidth(track.width());
                metadata.setSensorHeight(track.height());
                if (track.cmp1() != null) {
                    metadata.setBitDepth(track.cmp1().bitsPerSample());
                }
                break;
            }
        }

        // Set compressor version from CNCV box
        if (container.cncvBox() != null) {
            metadata.setCompressorVersion(container.cncvBox().versionString());
        }

        // Attempt to read CMT boxes from moov
        if (container.moovBox() != null) {
            readCmtBoxes(input, container.moovBox(), metadata);
        }

        LOG.debug("Extracted metadata: {}", metadata);
        return metadata;
    }

    private static void readCmtBoxes(ImageInputStream input, IsoBox moovBox, Cr3Metadata metadata) {
        IsoBox cmt1 = moovBox.findDescendant("CMT1");
        if (cmt1 != null) {
            LOG.debug("Found CMT1 (EXIF IFD0) at offset {}, size {}", cmt1.offset(), cmt1.size());
            // TODO: Parse TIFF IFD structure from CMT1
        }

        IsoBox cmt2 = moovBox.findDescendant("CMT2");
        if (cmt2 != null) {
            LOG.debug("Found CMT2 (EXIF ExifIFD) at offset {}, size {}", cmt2.offset(), cmt2.size());
            // TODO: Parse EXIF tags (ISO, shutter speed, aperture, focal length, date)
        }

        IsoBox cmt3 = moovBox.findDescendant("CMT3");
        if (cmt3 != null) {
            LOG.debug("Found CMT3 (Canon MakerNote) at offset {}, size {}", cmt3.offset(), cmt3.size());
            // TODO: Parse Canon MakerNote for camera model, sensor info, etc.
        }

        IsoBox cmt4 = moovBox.findDescendant("CMT4");
        if (cmt4 != null) {
            LOG.debug("Found CMT4 (GPS IFD) at offset {}, size {}", cmt4.offset(), cmt4.size());
            // TODO: Parse GPS data
        }

        IsoBox cctp = moovBox.findDescendant("CCTP");
        if (cctp != null) {
            LOG.debug("Found CCTP (tile parameters) at offset {}, size {}", cctp.offset(), cctp.size());
            // TODO: Parse tile layout for CRX decoding
        }
    }
}

