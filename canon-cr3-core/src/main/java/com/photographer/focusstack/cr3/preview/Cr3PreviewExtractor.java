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
     * @param input     the image input stream for the CR3 file
     * @param container the parsed CR3 container
     * @return the preview image as a BufferedImage
     * @throws Cr3Exception if no preview track is found
     * @throws IOException  if an I/O error occurs
     */
    public static BufferedImage extractPreview(ImageInputStream input, Cr3Container container)
            throws Cr3Exception, IOException {
        Cr3Track previewTrack = findTrackByType(container, Cr3Track.TrackType.JPEG_PREVIEW);
        if (previewTrack == null) {
            throw new Cr3Exception("No JPEG preview track found in CR3 file");
        }
        return readJpegTrack(input, previewTrack);
    }

    /**
     * Extracts the JPEG thumbnail image from the CR3 file.
     *
     * @param input     the image input stream for the CR3 file
     * @param container the parsed CR3 container
     * @return the thumbnail image as a BufferedImage
     * @throws Cr3Exception if no thumbnail track is found
     * @throws IOException  if an I/O error occurs
     */
    public static BufferedImage extractThumbnail(ImageInputStream input, Cr3Container container)
            throws Cr3Exception, IOException {
        Cr3Track thumbTrack = findTrackByType(container, Cr3Track.TrackType.JPEG_THUMBNAIL);
        if (thumbTrack == null) {
            throw new Cr3Exception("No JPEG thumbnail track found in CR3 file");
        }
        return readJpegTrack(input, thumbTrack);
    }

    /**
     * Extracts raw JPEG bytes for the main preview image.
     *
     * @param input     the image input stream for the CR3 file
     * @param container the parsed CR3 container
     * @return the JPEG bytes
     * @throws Cr3Exception if no preview track is found
     * @throws IOException  if an I/O error occurs
     */
    public static byte[] extractPreviewBytes(ImageInputStream input, Cr3Container container)
            throws Cr3Exception, IOException {
        Cr3Track previewTrack = findTrackByType(container, Cr3Track.TrackType.JPEG_PREVIEW);
        if (previewTrack == null) {
            throw new Cr3Exception("No JPEG preview track found in CR3 file");
        }
        return readTrackData(input, previewTrack);
    }

    /**
     * Extracts raw JPEG bytes for the thumbnail image.
     *
     * @param input     the image input stream for the CR3 file
     * @param container the parsed CR3 container
     * @return the JPEG bytes
     * @throws Cr3Exception if no thumbnail track is found
     * @throws IOException  if an I/O error occurs
     */
    public static byte[] extractThumbnailBytes(ImageInputStream input, Cr3Container container)
            throws Cr3Exception, IOException {
        Cr3Track thumbTrack = findTrackByType(container, Cr3Track.TrackType.JPEG_THUMBNAIL);
        if (thumbTrack == null) {
            throw new Cr3Exception("No JPEG thumbnail track found in CR3 file");
        }
        return readTrackData(input, thumbTrack);
    }

    /**
     * Checks whether the container has a JPEG preview.
     *
     * @param container the CR3 container
     * @return {@code true} if a preview track exists
     */
    public static boolean hasPreview(Cr3Container container) {
        return findTrackByType(container, Cr3Track.TrackType.JPEG_PREVIEW) != null;
    }

    /**
     * Checks whether the container has a JPEG thumbnail.
     *
     * @param container the CR3 container
     * @return {@code true} if a thumbnail track exists
     */
    public static boolean hasThumbnail(Cr3Container container) {
        return findTrackByType(container, Cr3Track.TrackType.JPEG_THUMBNAIL) != null;
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
        if (track.dataSize() <= 0) {
            throw new IOException("Track " + track.index() + " has no data (size=" + track.dataSize() + ")");
        }
        if (track.dataSize() > 50 * 1024 * 1024) {
            throw new IOException("Track " + track.index() + " data too large: " + track.dataSize() + " bytes");
        }
        byte[] data = new byte[(int) track.dataSize()];
        input.seek(track.dataOffset());
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
