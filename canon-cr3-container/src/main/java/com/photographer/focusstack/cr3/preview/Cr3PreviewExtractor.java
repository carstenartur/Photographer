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

/** Extracts embedded JPEG preview and thumbnail images from a CR3 file. */
public final class Cr3PreviewExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3PreviewExtractor.class);

    private Cr3PreviewExtractor() {
    }

    public static BufferedImage extractPreview(ImageInputStream input, Cr3Container container)
            throws Cr3Exception, IOException {
        Cr3Track previewTrack = findTrackByType(container, Cr3Track.TrackType.JPEG_PREVIEW);
        if (previewTrack != null) {
            return readJpegTrack(input, previewTrack);
        }
        if (container.prvwBox() != null && container.prvwBox().jpegData() != null
                && container.prvwBox().jpegData().length > 0) {
            LOG.debug("No JPEG_PREVIEW track — using PRVW box ({}×{}, {} bytes)",
                    container.prvwBox().width(), container.prvwBox().height(),
                    container.prvwBox().jpegData().length);
            return readJpegBytes(container.prvwBox().jpegData(), "PRVW");
        }
        throw new Cr3Exception("No JPEG preview track found in CR3 file");
    }

    public static BufferedImage extractThumbnail(ImageInputStream input, Cr3Container container)
            throws Cr3Exception, IOException {
        Cr3Track thumbTrack = findTrackByType(container, Cr3Track.TrackType.JPEG_THUMBNAIL);
        if (thumbTrack != null) {
            return readJpegTrack(input, thumbTrack);
        }
        if (container.thmbBox() != null && container.thmbBox().jpegData() != null
                && container.thmbBox().jpegData().length > 0) {
            LOG.debug("No JPEG_THUMBNAIL track — using THMB box ({}×{}, {} bytes)",
                    container.thmbBox().width(), container.thmbBox().height(),
                    container.thmbBox().jpegData().length);
            return readJpegBytes(container.thmbBox().jpegData(), "THMB");
        }
        throw new Cr3Exception("No JPEG thumbnail track found in CR3 file");
    }

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

    public static boolean hasPreview(Cr3Container container) {
        if (findTrackByType(container, Cr3Track.TrackType.JPEG_PREVIEW) != null) return true;
        return container.prvwBox() != null && container.prvwBox().jpegData() != null
                && container.prvwBox().jpegData().length > 0;
    }

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
