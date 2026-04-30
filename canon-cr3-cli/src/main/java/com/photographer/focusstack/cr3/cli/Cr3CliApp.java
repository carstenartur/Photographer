package com.photographer.focusstack.cr3.cli;

import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.bmff.IsoBmffReader;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.container.SensorCropGeometry;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import com.photographer.focusstack.cr3.research.SubbandStatistics;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line debug tool for Canon CR3 files.
 *
 * <p>Usage:</p>
 * <pre>
 * java -jar canon-cr3-cli.jar info    &lt;file.cr3&gt;
 * java -jar canon-cr3-cli.jar boxes   &lt;file.cr3&gt;
 * java -jar canon-cr3-cli.jar preview &lt;file.cr3&gt; [output.jpg]
 * java -jar canon-cr3-cli.jar thumb   &lt;file.cr3&gt; [output.jpg]
 * </pre>
 */
public final class Cr3CliApp {

    private Cr3CliApp() {
        // main class
    }

    /**
     * Main entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        String filePath = args[1];

        try {
            switch (command) {
                case "info" -> showInfo(filePath);
                case "boxes" -> showBoxes(filePath);
                case "dump-box-tree" -> dumpBoxTree(filePath);
                case "dump-metadata" -> dumpMetadata(filePath);
                case "dump-crop" -> dumpCrop(filePath);
                case "dump-raw-layout" -> dumpRawLayout(filePath);
                case "list-subbands" -> listSubbands(filePath);
                case "dump-subband" -> dumpSubband(filePath, args);
                case "subband-stats" -> subbandStats(filePath, args);
                case "preview" -> extractPreview(filePath, args.length > 2 ? args[2] : "preview.jpg");
                case "thumb" -> extractThumbnail(filePath, args.length > 2 ? args[2] : "thumbnail.jpg");
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static void showInfo(String filePath) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(new File(filePath))) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(input);

            Cr3Container container = decoder.getContainer();
            Cr3Metadata metadata = decoder.getMetadata();

            System.out.println("=== CR3 File Info ===");
            System.out.println("File: " + filePath);
            System.out.println("Brand: " + container.ftypBox().majorBrand());
            System.out.println("Compatible: " + container.ftypBox().compatibleBrands());
            System.out.println("Tracks: " + container.trackCount());
            System.out.println();

            for (Cr3Track track : container.tracks()) {
                System.out.printf("Track %d: %s  %dx%d  codec=%s  offset=%d  size=%d%n",
                        track.index(), track.type(), track.width(), track.height(),
                        track.codecType(), track.dataOffset(), track.dataSize());
            }
            System.out.println();

            System.out.println("=== Metadata ===");
            System.out.println("Camera: " + metadata.cameraMake() + " " + metadata.cameraModel());
            System.out.println("Sensor: " + metadata.sensorWidth() + "x" + metadata.sensorHeight());
            System.out.println("Visible: " + metadata.visibleWidth() + "x" + metadata.visibleHeight());
            System.out.println("Bit depth: " + metadata.bitDepth());
        }
    }

    private static void showBoxes(String filePath) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(new File(filePath))) {
            Cr3Container container = Cr3Parser.parse(input);

            System.out.println("=== Box Structure ===");
            for (IsoBox box : container.allBoxes()) {
                printBox(box, 0);
            }
        }
    }

    /**
     * Opens an ImageInputStream for the given file path, throwing if the file cannot be opened.
     */
    private static ImageInputStream openInput(String filePath) throws IOException {
        ImageInputStream input = ImageIO.createImageInputStream(new File(filePath));
        if (input == null) {
            throw new IOException("Cannot open file: " + filePath);
        }
        return input;
    }

    /**
     * Dumps the raw ISO-BMFF box tree for any BMFF file (does not require CR3 validation).
     * Shows all boxes with offset, length, and type in a hierarchical tree format.
     *
     * @param filePath path to the BMFF/CR3 file
     * @throws Exception if an I/O error occurs
     */
    static void dumpBoxTree(String filePath) throws Exception {
        try (ImageInputStream input = openInput(filePath)) {
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);
            StringBuilder sb = new StringBuilder();
            for (IsoBox box : boxes) {
                formatBoxTree(box, 0, sb);
            }
            System.out.print(sb);
        }
    }

    /**
     * Formats a box tree into the provided StringBuilder.
     * Visible for testing.
     */
    static void formatBoxTree(IsoBox box, int depth, StringBuilder sb) {
        String indent = "  ".repeat(depth);
        sb.append(String.format("%s%s  offset=%-10d  size=%-10d%n",
                indent, box.type(), box.offset(), box.size()));
        for (IsoBox child : box.children()) {
            formatBoxTree(child, depth + 1, sb);
        }
    }

    private static void printBox(IsoBox box, int indent) {
        String prefix = "  ".repeat(indent);
        System.out.printf("%s[%s] offset=%d size=%d%n", prefix, box.type(), box.offset(), box.size());
        for (IsoBox child : box.children()) {
            printBox(child, indent + 1);
        }
    }

    /**
     * Dumps metadata extracted from the CR3 file.
     */
    static void dumpMetadata(String filePath) throws Exception {
        try (ImageInputStream input = openInput(filePath)) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(input);

            Cr3Metadata metadata = decoder.getMetadata();
            System.out.println("=== CR3 Metadata ===");
            System.out.println("Camera: " + metadata.cameraMake() + " " + metadata.cameraModel());
            System.out.println("Sensor: " + metadata.sensorWidth() + "x" + metadata.sensorHeight());
            System.out.println("Visible: " + metadata.visibleWidth() + "x" + metadata.visibleHeight());
            System.out.println("Active Area: left=" + metadata.activeAreaLeft()
                    + " top=" + metadata.activeAreaTop()
                    + " right=" + metadata.activeAreaRight()
                    + " bottom=" + metadata.activeAreaBottom());
            System.out.println("Bit Depth: " + metadata.bitDepth());
            System.out.println("ISO: " + metadata.isoSpeed());
            System.out.println("Shutter: " + metadata.shutterSpeed());
            System.out.println("Aperture: " + metadata.aperture());
            System.out.println("Focal Length: " + metadata.focalLength());
            System.out.println("Date Taken: " + metadata.dateTaken());
        }
    }

    /**
     * Dumps crop/sensor geometry information from the CR3 file.
     */
    static void dumpCrop(String filePath) throws Exception {
        try (ImageInputStream input = openInput(filePath)) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(input);

            Cr3Metadata metadata = decoder.getMetadata();
            SensorCropGeometry geometry = SensorCropGeometry.withActiveArea(
                    metadata.sensorWidth(), metadata.sensorHeight(),
                    metadata.activeAreaLeft(), metadata.activeAreaTop(),
                    metadata.activeAreaRight(), metadata.activeAreaBottom());

            System.out.println("=== Sensor/Crop Geometry ===");
            System.out.println("Full Sensor: " + geometry.sensorWidth() + "x" + geometry.sensorHeight());
            System.out.println("Active Area: " + geometry.activeWidth() + "x" + geometry.activeHeight()
                    + " [left=" + geometry.activeLeft() + " top=" + geometry.activeTop()
                    + " right=" + geometry.activeRight() + " bottom=" + geometry.activeBottom() + "]");
            if (geometry.hasDefaultCrop()) {
                System.out.println("Default Crop: " + geometry.defaultCropWidth() + "x" + geometry.defaultCropHeight()
                        + " [left=" + geometry.defaultCropLeft() + " top=" + geometry.defaultCropTop()
                        + " right=" + geometry.defaultCropRight() + " bottom=" + geometry.defaultCropBottom() + "]");
            } else {
                System.out.println("Default Crop: (not set)");
            }
            int[] best = geometry.bestOutputDimensions();
            System.out.println("Best Output: " + best[0] + "x" + best[1]);
        }
    }

    /**
     * Dumps RAW data layout information from the CR3 file.
     */
    static void dumpRawLayout(String filePath) throws Exception {
        try (ImageInputStream input = openInput(filePath)) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(input);

            Cr3Container container = decoder.getContainer();

            System.out.println("=== RAW Data Layout ===");
            System.out.println("Tracks: " + container.trackCount());
            System.out.println();

            for (Cr3Track track : container.tracks()) {
                System.out.printf("Track %d: %s%n", track.index(), track.type());
                System.out.printf("  Codec:       %s%n", track.codecType());
                System.out.printf("  Compression: %s (supported=%s)%n",
                        track.compressionMode(), track.compressionMode().isSupported());
                System.out.printf("  Dimensions:  %dx%d%n", track.width(), track.height());
                System.out.printf("  Data Offset: %d (0x%X)%n", track.dataOffset(), track.dataOffset());
                System.out.printf("  Data Size:   %d bytes%n", track.dataSize());
                System.out.println();
            }
        }
    }

    private static void extractPreview(String filePath, String outputPath) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(new File(filePath))) {
            Cr3Container container = Cr3Parser.parse(input);
            BufferedImage preview = Cr3PreviewExtractor.extractPreview(input, container);
            ImageIO.write(preview, "JPEG", new File(outputPath));
            System.out.printf("Preview extracted: %dx%d -> %s%n",
                    preview.getWidth(), preview.getHeight(), outputPath);
        }
    }

    private static void extractThumbnail(String filePath, String outputPath) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(new File(filePath))) {
            Cr3Container container = Cr3Parser.parse(input);
            BufferedImage thumb = Cr3PreviewExtractor.extractThumbnail(input, container);
            ImageIO.write(thumb, "JPEG", new File(outputPath));
            System.out.printf("Thumbnail extracted: %dx%d -> %s%n",
                    thumb.getWidth(), thumb.getHeight(), outputPath);
        }
    }

    /**
     * Lists every CRX subband in the RAW track of a CR3 file with structural
     * metadata. Supports the cleanroom CRX entropy-codec investigation by giving
     * developers a quick overview of which subbands are available for dumping.
     *
     * @param filePath path to the CR3 file
     * @throws Exception if reading fails
     */
    static void listSubbands(String filePath) throws Exception {
        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(new File(filePath));
        System.out.println("=== CRX Subbands ===");
        System.out.println("File: " + filePath);
        System.out.println("Count: " + subs.size());
        if (subs.isEmpty()) {
            System.out.println("(no RAW track in this CR3 file)");
            return;
        }
        System.out.println();
        System.out.printf("%-3s %-3s %-3s %-3s %-12s %-10s %-3s %-7s %-12s %-7s%n",
                "seq", "ti", "pl", "sb", "fileOffset", "size", "q", "partial", "planeWxH", "ver");
        int seq = 0;
        for (SubbandExtractor.SubbandRef r : subs) {
            System.out.printf("%-3d %-3d %-3d %-3d %-12d %-10d %-3d %-7s %dx%-9d %-7s%n",
                    seq++, r.tileSequence(), r.planeIndex(), r.subbandIndex(),
                    r.dataOffsetInFile(), r.dataSize(), r.quantValue(),
                    r.supportsPartial(), r.planeWidth(), r.planeHeight(),
                    r.codecVersion());
        }
    }

    /**
     * Dumps a single CRX subband's raw entropy-coded bytes to a {@code .bits}
     * file with a sidecar {@code .meta.json} describing exactly what was dumped.
     *
     * <p>Args layout: {@code dump-subband &lt;file.cr3&gt; &lt;seq&gt; [outBase]}.
     * If {@code outBase} is omitted, files are written next to the CR3 file as
     * {@code &lt;cr3basename&gt;.sb&lt;seq&gt;.bits} / {@code .meta.json}.</p>
     *
     * @param filePath path to the CR3 file
     * @param args full argv
     * @throws Exception if the operation fails
     */
    static void dumpSubband(String filePath, String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: cr3-cli dump-subband <file.cr3> <seq> [outBase]");
            System.exit(1);
            return;
        }
        int seq = Integer.parseInt(args[2]);
        String outBase;
        if (args.length > 3) {
            outBase = args[3];
        } else {
            String name = new File(filePath).getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            outBase = base + ".sb" + seq;
        }

        File cr3 = new File(filePath);
        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(cr3);
        if (seq < 0 || seq >= subs.size()) {
            throw new IOException("Subband sequence " + seq + " out of range (have " + subs.size() + ")");
        }
        SubbandExtractor.SubbandRef ref = subs.get(seq);
        byte[] bytes = SubbandExtractor.read(cr3, ref);

        Path bitsPath = Path.of(outBase + ".bits");
        Path metaPath = Path.of(outBase + ".meta.json");
        Files.write(bitsPath, bytes);
        Files.writeString(metaPath, formatSubbandMetaJson(filePath, seq, ref));

        System.out.printf("Wrote %s (%d bytes) and %s%n",
                bitsPath, bytes.length, metaPath);
    }

    /**
     * Formats a subband descriptor as a small JSON document. Keys mirror the
     * record component names of {@link SubbandExtractor.SubbandRef} so the file
     * is self-describing for any future reader.
     *
     * @param sourceFile the source CR3 path
     * @param seq the sequence number used to identify the subband on the command line
     * @param r the subband descriptor
     * @return a JSON string ending in a single trailing newline
     */
    static String formatSubbandMetaJson(String sourceFile, int seq, SubbandExtractor.SubbandRef r) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append("  \"sourceFile\": ").append(jsonString(sourceFile)).append(",\n");
        sb.append("  \"sequence\": ").append(seq).append(",\n");
        sb.append("  \"tileIndex\": ").append(r.tileIndex()).append(",\n");
        sb.append("  \"tileSequence\": ").append(r.tileSequence()).append(",\n");
        sb.append("  \"planeIndex\": ").append(r.planeIndex()).append(",\n");
        sb.append("  \"subbandIndex\": ").append(r.subbandIndex()).append(",\n");
        sb.append("  \"dataOffsetInFile\": ").append(r.dataOffsetInFile()).append(",\n");
        sb.append("  \"dataSize\": ").append(r.dataSize()).append(",\n");
        sb.append("  \"quantValue\": ").append(r.quantValue()).append(",\n");
        sb.append("  \"supportsPartial\": ").append(r.supportsPartial()).append(",\n");
        sb.append("  \"roundedBits\": ").append(r.roundedBits()).append(",\n");
        sb.append("  \"trackWidth\": ").append(r.trackWidth()).append(",\n");
        sb.append("  \"trackHeight\": ").append(r.trackHeight()).append(",\n");
        sb.append("  \"bitDepth\": ").append(r.bitDepth()).append(",\n");
        sb.append("  \"codecVersion\": ").append(jsonString(r.codecVersion().name())).append(",\n");
        sb.append("  \"tileWidth\": ").append(r.tileWidth()).append(",\n");
        sb.append("  \"tileHeight\": ").append(r.tileHeight()).append(",\n");
        sb.append("  \"planeWidth\": ").append(r.planeWidth()).append(",\n");
        sb.append("  \"planeHeight\": ").append(r.planeHeight()).append(",\n");
        sb.append("  \"waveletLevels\": ").append(r.waveletLevels()).append(",\n");
        sb.append("  \"encodingType\": ").append(r.encodingType()).append(",\n");
        sb.append("  \"cncvVersion\": ").append(jsonString(r.cncvVersion())).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Escapes a string for JSON or returns {@code "null"} when the input is {@code null}. */
    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Computes and prints byte/bit-level statistics for a single CRX subband,
     * either selected by sequence number from a CR3 file
     * ({@code subband-stats &lt;file.cr3&gt; &lt;seq&gt;}) or read from a previously
     * dumped {@code .bits} file ({@code subband-stats --bits &lt;file.bits&gt;}).
     *
     * @param filePath the first positional argument
     * @param args full argv
     * @throws Exception if the operation fails
     */
    static void subbandStats(String filePath, String[] args) throws Exception {
        byte[] bytes;
        String label;
        if ("--bits".equals(filePath)) {
            if (args.length < 3) {
                System.err.println("Usage: cr3-cli subband-stats --bits <file.bits>");
                System.exit(1);
                return;
            }
            String bitsFile = args[2];
            bytes = Files.readAllBytes(Path.of(bitsFile));
            label = bitsFile;
        } else {
            if (args.length < 3) {
                System.err.println("Usage: cr3-cli subband-stats <file.cr3> <seq>");
                System.exit(1);
                return;
            }
            int seq = Integer.parseInt(args[2]);
            File cr3 = new File(filePath);
            List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(cr3);
            if (seq < 0 || seq >= subs.size()) {
                throw new IOException("Subband sequence " + seq + " out of range (have " + subs.size() + ")");
            }
            bytes = SubbandExtractor.read(cr3, subs.get(seq));
            label = filePath + " sb" + seq;
        }

        SubbandStatistics stats = SubbandStatistics.compute(bytes);
        System.out.println("=== Subband Statistics: " + label + " ===");
        System.out.print(stats.summary());
    }

    private static void printUsage() {
        System.out.println("Canon CR3 Debug CLI");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  cr3-cli info          <file.cr3>              Show file info and metadata");
        System.out.println("  cr3-cli boxes         <file.cr3>              Dump box structure (CR3 only)");
        System.out.println("  cr3-cli dump-box-tree <file.cr3>              Dump all ISO-BMFF boxes with offset/size");
        System.out.println("  cr3-cli dump-metadata <file.cr3>              Dump extracted metadata (EXIF, MakerNote)");
        System.out.println("  cr3-cli dump-crop     <file.cr3>              Dump sensor/crop geometry");
        System.out.println("  cr3-cli dump-raw-layout <file.cr3>            Dump RAW data layout (tracks, codecs)");
        System.out.println("  cr3-cli list-subbands   <file.cr3>            List CRX subbands in the RAW track (research)");
        System.out.println("  cr3-cli dump-subband    <file.cr3> <seq> [outBase]");
        System.out.println("                                                 Dump one CRX subband to <outBase>.bits + .meta.json");
        System.out.println("  cr3-cli subband-stats   <file.cr3> <seq>      Print byte/bit statistics for one CRX subband");
        System.out.println("  cr3-cli subband-stats   --bits <file.bits>   Print byte/bit statistics for a previously dumped .bits");
        System.out.println("  cr3-cli preview       <file.cr3> [output.jpg] Extract JPEG preview");
        System.out.println("  cr3-cli thumb         <file.cr3> [output.jpg] Extract JPEG thumbnail");
    }
}
