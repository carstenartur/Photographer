package com.photographer.focusstack.cr3.preview;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Extracts JPEG preview and thumbnail images from a CR3 file.
 *
 * <p>CR3 files contain embedded JPEG images in tracks 1 and 2:</p>
 * <ul>
 *   <li>Track 0: Small JPEG thumbnail (~160×120)</li>
 *   <li>Track 1: Main JPEG preview (~1620×1080)</li>
 * </ul>
 *
 * <p>This extractor reads the JPEG data directly from the file using the
 * track offsets parsed by {@link com.photographer.focusstack.cr3.container.Cr3Parser}.</p>
 */
public final class Cr3PreviewExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3PreviewExtractor.class);

    private Cr3PreviewExtractor() {
        // utility class
    }

    /**
     * Extracts the main JPEG preview image from the CR3 file.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Track typed {@link Cr3Track.TrackType#JPEG_PREVIEW} (synthetic test CR3s)</li>
     *   <li>{@code PRVW} box embedded in the top-level {@code uuid} box
     *       (real Canon camera CR3 files, e.g. PowerShot G5 X Mark II)</li>
     * </ol>
     *
     * @param input     the image input stream for the CR3 file
     * @param container the parsed CR3 container
     * @return the preview image as a BufferedImage
     * @throws Cr3Exception if no preview source is found
     * @throws IOException  if an I/O error occurs
     */
    public static BufferedImage extractPreview(ImageInputStream input, Cr3Container container)
            throws Cr3Exception, IOException {
        Cr3Track previewTrack = findTrackByType(container, Cr3Track.TrackType.JPEG_PREVIEW);
        if (previewTrack != null) {
            return readJpegTrack(input, previewTrack);
        }
        // Fall back to the PRVW box (present in real Canon camera files)
        if (container.prvwBox() != null && container.prvwBox().jpegData() != null
                && container.prvwBox().jpegData().length > 0) {
            LOG.debug("No JPEG_PREVIEW track — using PRVW box ({}×{}, {} bytes)",
                    container.prvwBox().width(), container.prvwBox().height(),
                    container.prvwBox().jpegData().length);
            return readJpegBytes(container.prvwBox().jpegData(), "PRVW");
        }
        throw new Cr3Exception("No JPEG preview track found in CR3 file");
    }

    /**
     * Extracts the JPEG thumbnail image from the CR3 file.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Track typed {@link Cr3Track.TrackType#JPEG_THUMBNAIL}</li>
     *   <li>{@code THMB} box embedded in {@code moov/uuid}
     *       (real Canon camera CR3 files)</li>
     * </ol>
     *
     * @param input     the image input stream for the CR3 file
     * @param container the parsed CR3 container
     * @return the thumbnail image as a BufferedImage
     * @throws Cr3Exception if no thumbnail source is found
     * @throws IOException  if an I/O error occurs
     */
    public static BufferedImage extractThumbnail(ImageInputStream input, Cr3Container container)
            throws Cr3Exception, IOException {
        Cr3Track thumbTrack = findTrackByType(container, Cr3Track.TrackType.JPEG_THUMBNAIL);
        if (thumbTrack != null) {
            return readJpegTrack(input, thumbTrack);
        }
        // Fall back to the THMB box (present in real Canon camera files)
        if (container.thmbBox() != null && container.thmbBox().jpegData() != null
                && container.thmbBox().jpegData().length > 0) {
            LOG.debug("No JPEG_THUMBNAIL track — using THMB box ({}×{}, {} bytes)",
                    container.thmbBox().width(), container.thmbBox().height(),
                    container.thmbBox().jpegData().length);
            return readJpegBytes(container.thmbBox().jpegData(), "THMB");
        }
        throw new Cr3Exception("No JPEG thumbnail track found in CR3 file");
    }

    /**
     * Extracts raw JPEG bytes for the main preview image.
     *
     * @param input     the image input stream for the CR3 file
     * @param container the parsed CR3 container
     * @return the JPEG bytes
     * @throws Cr3Exception if no preview source is found
     * @throws IOException  if an I/O error occurs
     */
    public static byte[] extractPreviewBytes(ImageInputStream input, Cr3Container container)
            throws Cr3Exception, IOException {
        Cr3Track previewTrack = findTrackByType(container, Cr3Track.TrackType.JPEG_PREVIEW);
        if (previewTrack != null) {
            return readTrackData(input, previewTrack);
        }
        if (container.prvwBox() != null && container.prvwBox().jpegData() != null
                && container.prvwBox().jpegData().length > 0) {
            return container.prvwBox().jpegData();
        }
        throw new Cr3Exception("No JPEG preview track found in CR3 file");
    }

    /**
     * Extracts raw JPEG bytes for the thumbnail image.
     *
     * @param input     the image input stream for the CR3 file
     * @param container the parsed CR3 container
     * @return the JPEG bytes
     * @throws Cr3Exception if no thumbnail source is found
     * @throws IOException  if an I/O error occurs
     */
    public static byte[] extractThumbnailBytes(ImageInputStream input, Cr3Container container)
            throws Cr3Exception, IOException {
        Cr3Track thumbTrack = findTrackByType(container, Cr3Track.TrackType.JPEG_THUMBNAIL);
        if (thumbTrack != null) {
            return readTrackData(input, thumbTrack);
        }
        if (container.thmbBox() != null && container.thmbBox().jpegData() != null
                && container.thmbBox().jpegData().length > 0) {
            return container.thmbBox().jpegData();
        }
        throw new Cr3Exception("No JPEG thumbnail track found in CR3 file");
    }

    /**
     * Checks whether the container has a JPEG preview (track or PRVW box).
     *
     * @param container the CR3 container
     * @return {@code true} if a preview source exists
     */
    public static boolean hasPreview(Cr3Container container) {
        if (findTrackByType(container, Cr3Track.TrackType.JPEG_PREVIEW) != null) return true;
        return container.prvwBox() != null && container.prvwBox().jpegData() != null
                && container.prvwBox().jpegData().length > 0;
    }

    /**
     * Checks whether the container has a JPEG thumbnail (track or THMB box).
     *
     * @param container the CR3 container
     * @return {@code true} if a thumbnail source exists
     */
    public static boolean hasThumbnail(Cr3Container container) {
        if (findTrackByType(container, Cr3Track.TrackType.JPEG_THUMBNAIL) != null) return true;
        return container.thmbBox() != null && container.thmbBox().jpegData() != null
                && container.thmbBox().jpegData().length > 0;
    }

    private static BufferedImage readJpegBytes(byte[] jpegData, String source)
            throws IOException, Cr3Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegData));
        if (image == null) {
            throw new Cr3Exception("Failed to decode JPEG data from " + source);
        }
        LOG.info("Extracted {}×{} JPEG from {}", image.getWidth(), image.getHeight(), source);
        return image;
    }

    private static BufferedImage readJpegTrack(ImageInputStream input, Cr3Track track)
            throws IOException, Cr3Exception {
        byte[] jpegData = readTrackData(input, track);
        LOG.debug("Read {} bytes of JPEG data from track {}", jpegData.length, track.index());

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegData));
        if (image == null) {
            throw new Cr3Exception("Failed to decode JPEG data from track " + track.index());
        }
        LOG.info("Extracted {}×{} JPEG from track {}", image.getWidth(), image.getHeight(), track.index());
        return image;
    }

    private static byte[] readTrackData(ImageInputStream input, Cr3Track track) throws IOException {
        long dataOffset = track.dataOffset();
        long dataSize = track.dataSize();
        if (dataSize <= 0) {
            throw new IOException("Track " + track.index() + " has no data (size=" + dataSize + ")");
        }
        if (dataSize > 50 * 1024 * 1024) {
            throw new IOException("Track " + track.index() + " data too large: " + dataSize + " bytes");
        }
        // Validate range against stream length before seeking/reading
        long streamLength = input.length();
        if (streamLength >= 0) {
            if (dataOffset < 0 || dataOffset > streamLength || dataSize > streamLength - dataOffset) {
                throw new IOException(
                        "Track " + track.index() + " data range is out of bounds: offset=" + dataOffset
                                + ", size=" + dataSize + ", streamLength=" + streamLength);
            }
        }
        byte[] data = new byte[(int) dataSize];
        input.seek(dataOffset);
        input.readFully(data);
        return data;
    }

    private static Cr3Track findTrackByType(Cr3Container container, Cr3Track.TrackType type) {
        for (Cr3Track track : container.tracks()) {
            if (track.type() == type) {
                return track;
            }
        }
        return null;
    }
}
