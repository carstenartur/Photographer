package com.photographer.focusstack.cr3.metadata;

import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.container.Cr3Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * Reads metadata from CR3 files independently of image data decoding.
 *
 * <p>This class is part of the architectural separation between metadata and image
 * data — a key lesson from LibRaw. Metadata and image data are separate problems
 * and should be handled by separate subsystems.</p>
 *
 * <p>CR3 metadata is stored in several locations within the container:</p>
 * <ul>
 *   <li>{@code CMT1} — EXIF IFD0 (main EXIF tags)</li>
 *   <li>{@code CMT2} — EXIF ExifIFD (exposure, lens info)</li>
 *   <li>{@code CMT3} — Canon MakerNote</li>
 *   <li>{@code CMT4} — GPS IFD</li>
 *   <li>{@code CNCV} — Canon Compressor Version</li>
 *   <li>{@code CCTP} — Canon CR3 Craw Tile Parameters</li>
 *   <li>{@code CTMD} — Timed Metadata</li>
 * </ul>
 *
 * TODO: Parse TIFF IFD structures from CMT1/CMT2 boxes
 * TODO: Parse Canon MakerNote from CMT3 box
 * TODO: Extract GPS data from CMT4 box
 * TODO: Parse CNCV for compressor version identification
 * TODO: Parse CCTP for tile parameters (needed for RAW decoding)
 * TODO: Extract timed metadata from CTMD
 */
public final class Cr3MetadataReader {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3MetadataReader.class);

    private Cr3MetadataReader() {
        // utility class
    }

    /**
     * Extracts metadata from a parsed CR3 container.
     *
     * <p>Currently extracts basic information from the container structure.
     * Full EXIF/MakerNote parsing is not yet implemented.</p>
     *
     * @param input     the image input stream
     * @param container the parsed CR3 container
     * @return the extracted metadata
     * @throws IOException if an I/O error occurs
     */
    public static Cr3Metadata read(ImageInputStream input, Cr3Container container)
            throws IOException {
        Cr3Metadata metadata = new Cr3Metadata();
        metadata.setCameraMake("Canon");

        // Set sensor dimensions from RAW track if available
        for (var track : container.tracks()) {
            if (track.type() == com.photographer.focusstack.cr3.container.Cr3Track.TrackType.CRX_RAW) {
                metadata.setSensorWidth(track.width());
                metadata.setSensorHeight(track.height());
                break;
            }
        }

        // Attempt to read CMT boxes from moov
        if (container.moovBox() != null) {
            readCmtBoxes(input, container.moovBox(), metadata);
        }

        LOG.debug("Extracted metadata: {}", metadata);
        return metadata;
    }

    private static void readCmtBoxes(ImageInputStream input, IsoBox moovBox, Cr3Metadata metadata) {
        // CMT1: EXIF IFD0
        IsoBox cmt1 = moovBox.findDescendant("CMT1");
        if (cmt1 != null) {
            LOG.debug("Found CMT1 (EXIF IFD0) at offset {}, size {}", cmt1.offset(), cmt1.size());
            // TODO: Parse TIFF IFD structure from CMT1
        }

        // CMT2: EXIF ExifIFD
        IsoBox cmt2 = moovBox.findDescendant("CMT2");
        if (cmt2 != null) {
            LOG.debug("Found CMT2 (EXIF ExifIFD) at offset {}, size {}", cmt2.offset(), cmt2.size());
            // TODO: Parse EXIF tags (ISO, shutter speed, aperture, focal length, date)
        }

        // CMT3: Canon MakerNote
        IsoBox cmt3 = moovBox.findDescendant("CMT3");
        if (cmt3 != null) {
            LOG.debug("Found CMT3 (Canon MakerNote) at offset {}, size {}", cmt3.offset(), cmt3.size());
            // TODO: Parse Canon MakerNote for camera model, sensor info, etc.
        }

        // CMT4: GPS IFD
        IsoBox cmt4 = moovBox.findDescendant("CMT4");
        if (cmt4 != null) {
            LOG.debug("Found CMT4 (GPS IFD) at offset {}, size {}", cmt4.offset(), cmt4.size());
            // TODO: Parse GPS data
        }

        // CNCV: Canon Compressor Version
        IsoBox cncv = moovBox.findDescendant("CNCV");
        if (cncv != null) {
            LOG.debug("Found CNCV (compressor version) at offset {}, size {}", cncv.offset(), cncv.size());
            // TODO: Read compressor version string to determine RAW vs C-RAW
        }

        // CCTP: Canon Craw Tile Parameters
        IsoBox cctp = moovBox.findDescendant("CCTP");
        if (cctp != null) {
            LOG.debug("Found CCTP (tile parameters) at offset {}, size {}", cctp.offset(), cctp.size());
            // TODO: Parse tile layout for CRX decoding
        }
    }
}
