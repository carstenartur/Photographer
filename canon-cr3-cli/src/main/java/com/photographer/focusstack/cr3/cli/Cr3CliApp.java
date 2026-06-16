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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;
import picocli.CommandLine.Model.CommandSpec;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command-line debug tool for Canon CR3 files.
 *
 * <p>Usage:</p>
 * <pre>
 * java -jar canon-cr3-cli.jar info    &lt;file.cr3&gt;
 * java -jar canon-cr3-cli.jar boxes   &lt;file.cr3&gt;
 * java -jar canon-cr3-cli.jar preview &lt;file.cr3&gt; [output.jpg]
 * java -jar canon-cr3-cli.jar thumb   &lt;file.cr3&gt; [output.jpg]
 * java -jar canon-cr3-cli.jar decode  &lt;file.cr3&gt; [output.jpg]
 * java -jar canon-cr3-cli.jar decode-compare &lt;file.cr3&gt; [outBase]
 * java -jar canon-cr3-cli.jar pedestal-report &lt;file.cr3&gt;
 * </pre>
 */
@Command(
        name = "cr3-cli",
        mixinStandardHelpOptions = true,
        version = "cr3-cli 0.1.4-SNAPSHOT",
        exitCodeOnInvalidInput = 1,
        description = {
                "Command-line debug tool for Canon CR3 files.",
                "Exit codes: 0=success, 1=usage error, 2=file-not-found, 3=decode/runtime error."
        })
public final class Cr3CliApp implements Callable<Integer> {

    private static final String BITS_OPTION = "--bits";

    @Spec
    private CommandSpec commandSpec;

    @Parameters(index = "0", description = "Command name")
    private String command;

    @Parameters(index = "1", description = "Input CR3 file path or --bits sentinel for subband-stats")
    private String filePath;

    @Parameters(index = "2..*", arity = "0..*", description = "Additional command arguments")
    private List<String> extraArgs = new ArrayList<>();

    @Unmatched
    private List<String> unmatched = new ArrayList<>();

    /**
     * Main entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Cr3CliApp()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            return runCommand();
        } catch (CommandLine.ParameterException e) {
            throw e;
        } catch (FileNotFoundException e) {
            System.err.println("File not found: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 3;
        }
    }

    private int runCommand() throws Exception {
        CommandLine parsedCommandLine = commandSpec.commandLine();
        boolean bitsMode = "subband-stats".equals(command) && unmatched.contains(BITS_OPTION);
        if (bitsMode && (filePath == null || filePath.isBlank())) {
            throw new CommandLine.ParameterException(parsedCommandLine,
                    "Usage: cr3-cli subband-stats " + BITS_OPTION + " <file.bits>");
        }
        if (!bitsMode) {
            if (filePath == null || filePath.isBlank()) {
                throw new CommandLine.ParameterException(parsedCommandLine, "Missing input file path");
            }
            Path inputPath = Path.of(filePath);
            if (!Files.exists(inputPath)) {
                throw new FileNotFoundException(filePath);
            }
        }

        List<String> mergedArgs = new ArrayList<>();
        if (bitsMode) {
            mergedArgs.add(filePath);
        } else {
            mergedArgs.addAll(extraArgs);
            mergedArgs.addAll(unmatched);
        }
        String[] fullArgs = new String[2 + mergedArgs.size()];
        fullArgs[0] = command;
        fullArgs[1] = bitsMode ? BITS_OPTION : filePath;
        for (int i = 0; i < mergedArgs.size(); i++) {
            fullArgs[2 + i] = mergedArgs.get(i);
        }

        switch (command) {
            case "info" -> showInfo(filePath);
            case "boxes" -> showBoxes(filePath);
            case "dump-box-tree" -> dumpBoxTree(filePath);
            case "dump-metadata" -> dumpMetadata(filePath);
            case "dump-crop" -> dumpCrop(filePath);
            case "dump-raw-layout" -> dumpRawLayout(filePath);
            case "list-subbands" -> listSubbands(filePath);
            case "dump-subband" -> dumpSubband(filePath, fullArgs, parsedCommandLine);
            case "subband-stats" -> subbandStats(bitsMode ? BITS_OPTION : filePath, fullArgs, parsedCommandLine);
            case "preview" -> extractPreview(filePath, extraArgs.isEmpty() ? "preview.jpg" : extraArgs.getFirst());
            case "thumb" -> extractThumbnail(filePath, extraArgs.isEmpty() ? "thumbnail.jpg" : extraArgs.getFirst());
            case "decode" -> decodeRaw(filePath, extraArgs.isEmpty() ? null : extraArgs.getFirst(), /*compare*/ false);
            case "decode-compare" -> decodeRaw(filePath, extraArgs.isEmpty() ? null : extraArgs.getFirst(), /*compare*/ true);
            case "pedestal-report" -> pedestalReport(filePath);
            default -> throw new CommandLine.ParameterException(parsedCommandLine, "Unknown command: " + command);
        }
        return 0;
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
        if (!Files.exists(Path.of(filePath))) {
            throw new FileNotFoundException(filePath);
        }
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
     * Decodes the RAW track of a CR3 file through the full processing pipeline
     * (black level → crop → white balance → demosaic → colour matrix → sRGB)
     * and writes the result as a JPEG.
     *
     * <p>Commands:</p>
     * <pre>
     *   cr3-cli decode &lt;file.cr3&gt; [output.jpg]
     *       Decodes RAW → processed sRGB JPEG.
     *
     *   cr3-cli decode-compare &lt;file.cr3&gt; [outBase]
     *       Writes three files derived from {@code outBase} (default = CR3 basename):
     *       {@code <outBase>-raw.jpg}     — full RAW decode
     *       {@code <outBase>-preview.jpg} — embedded JPEG preview (JPEG_BIG track)
     *       {@code <outBase>-thumb.jpg}   — embedded thumbnail
     *       Intended for side-by-side visual comparison.
     * </pre>
     *
     * @param filePath path to the CR3 file
     * @param outputArg optional output path / base name (may be {@code null})
     * @param compare   when {@code true}, also write the embedded preview and thumbnail
     */
    private static void decodeRaw(String filePath, String outputArg, boolean compare) throws Exception {
        // Derive base name from the CR3 file when no output argument is given.
        String base;
        if (outputArg != null && !outputArg.isBlank()) {
            // Strip a trailing .jpg extension so we can append our own suffixes.
            base = outputArg.replaceAll("(?i)\\.jpe?g$", "");
        } else {
            String name = new File(filePath).getName();
            int dot = name.lastIndexOf('.');
            base = (dot > 0) ? name.substring(0, dot) : name;
        }

        String rawOut = compare ? (base + "-raw.jpg") : (base + ".jpg");

        try (Cr3Decoder decoder = new Cr3Decoder()) {
            decoder.setInput(Path.of(filePath));

            // --- Full RAW → processed sRGB decode ---
            long t0 = System.currentTimeMillis();
            BufferedImage rawImg = decoder.decode(Cr3DecodeOptions.rawProcessed());
            long dt = System.currentTimeMillis() - t0;
            writeJpeg(rawImg, rawOut, 92);
            System.out.printf("RAW decoded (%dx%d, %.1f s) -> %s%n",
                    rawImg.getWidth(), rawImg.getHeight(), dt / 1000.0, rawOut);

            if (compare) {
                // --- Embedded preview (JPEG_BIG) ---
                String prevOut = base + "-preview.jpg";
                BufferedImage prev = decoder.decode(Cr3DecodeOptions.defaults()); // PREVIEW mode
                writeJpeg(prev, prevOut, 95);
                System.out.printf("Embedded preview (%dx%d) -> %s%n",
                        prev.getWidth(), prev.getHeight(), prevOut);

                // --- Embedded thumbnail ---
                String thumbOut = base + "-thumb.jpg";
                BufferedImage thumb = decoder.decode(Cr3DecodeOptions.defaults()
                        .outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL));
                writeJpeg(thumb, thumbOut, 90);
                System.out.printf("Embedded thumbnail (%dx%d) -> %s%n",
                        thumb.getWidth(), thumb.getHeight(), thumbOut);

                System.out.println();
                System.out.println("Compare in your image viewer:");
                System.out.println("  " + rawOut + "   (full RAW decode, current decoder)");
                System.out.println("  " + prevOut + "   (camera-rendered JPEG, ground truth)");
                System.out.println("  " + thumbOut + "   (camera thumbnail)");
            }
        }
    }

    /** Writes a {@link BufferedImage} as JPEG at the given quality (0-100). */
    private static void writeJpeg(BufferedImage img, String path, int quality) throws IOException {
        var iter = ImageIO.getImageWritersByFormatName("jpeg");
        if (!iter.hasNext()) throw new IOException("No JPEG writer found");
        var writer = iter.next();
        var iwp = writer.getDefaultWriteParam();
        iwp.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        iwp.setCompressionQuality(quality / 100f);
        try (var fos = new FileOutputStream(path);
             var ios = ImageIO.createImageOutputStream(fos)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), iwp);
        } finally {
            writer.dispose();
        }
    }

    /**
     * Prints a diagnostic report for pedestal/black-level source resolution.
     *
     * <p>Usage: {@code cr3-cli pedestal-report <file.cr3>}</p>
     */
    static void pedestalReport(String filePath) throws Exception {
        try (Cr3Decoder decoder = new Cr3Decoder()) {
            decoder.setInput(Path.of(filePath));
            Cr3Decoder.PedestalReport report = decoder.pedestalReport();
            Cr3Metadata metadata = decoder.getMetadata();
            System.out.print(formatPedestalReport(filePath, metadata, report));
        }
    }

    static String formatPedestalReport(String filePath, Cr3Metadata metadata, Cr3Decoder.PedestalReport report) {
        StringBuilder sb = new StringBuilder(768);
        sb.append("=== Pedestal Report ===\n");
        sb.append("File: ").append(filePath).append('\n');
        sb.append("Sensor: ").append(report.rawWidth()).append('x').append(report.rawHeight()).append('\n');
        sb.append("ActiveAreaLeft (OB columns): ").append(report.activeAreaLeftColumns())
                .append(" (min required: ").append(report.minObColumnsRequired()).append(")\n");
        sb.append("Raw pixel[0,0]: ").append(report.rawFirstPixel()).append('\n');
        sb.append("Raw pixel[activeLeft,activeTop]: ").append(report.rawActiveAreaFirstPixel()).append('\n');
        sb.append("Resolved source: ").append(report.source()).append('\n');

        if (report.metadataPerChannelBlackLevels() != null) {
            sb.append("Metadata per-channel [R,Gr,Gb,B]: ")
                    .append(Arrays.toString(report.metadataPerChannelBlackLevels())).append('\n');
        } else {
            sb.append("Metadata per-channel [R,Gr,Gb,B]: (missing)\n");
        }

        if (metadata != null) {
            sb.append("Metadata ISO: ").append(metadata.isoSpeed()).append('\n');
            sb.append("Metadata active area: left=").append(metadata.activeAreaLeft())
                    .append(" top=").append(metadata.activeAreaTop())
                    .append(" right=").append(metadata.activeAreaRight())
                    .append(" bottom=").append(metadata.activeAreaBottom())
                    .append('\n');
        }

        sb.append("Resolved black level mode: ").append(report.blackLevel().mode()).append('\n');
        switch (report.blackLevel().mode()) {
            case GLOBAL -> sb.append("Resolved global level: ").append(report.blackLevel().globalLevel()).append('\n');
            case PER_CHANNEL -> sb.append("Resolved per-channel [R,Gr,Gb,B]: ")
                    .append(Arrays.toString(report.blackLevel().perChannelLevels())).append('\n');
            case PER_ROW -> sb.append("Resolved per-row minimum level: ")
                    .append(report.blackLevel().minPerRowLevel()).append('\n');
        }
        return sb.toString();
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
        System.out.printf("%-3s %-4s %-3s %-3s %-12s %-10s %-3s %-7s %-12s %-7s%n",
                "seq", "tSeq", "pl", "sb", "fileOffset", "size", "q", "partial", "planeWxH", "ver");
        int seq = 0;
        for (SubbandExtractor.SubbandRef r : subs) {
            System.out.printf("%-3d %-4d %-3d %-3d %-12d %-10d %-3d %-7s %dx%-9d %-7s%n",
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
    static void dumpSubband(String filePath, String[] args, CommandLine commandLine) throws Exception {
        if (args.length < 3) {
            throw new CommandLine.ParameterException(commandLine,
                    "Usage: cr3-cli dump-subband <file.cr3> <seq> [outBase]");
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
    static void subbandStats(String filePath, String[] args, CommandLine commandLine) throws Exception {
        byte[] bytes;
        String label;
        if (BITS_OPTION.equals(filePath)) {
            if (args.length < 3) {
                throw new CommandLine.ParameterException(commandLine,
                        "Usage: cr3-cli subband-stats " + BITS_OPTION + " <file.bits>");
            }
            String bitsFile = args[2];
            if (!Files.exists(Path.of(bitsFile))) {
                throw new FileNotFoundException(bitsFile);
            }
            bytes = Files.readAllBytes(Path.of(bitsFile));
            label = bitsFile;
        } else {
            if (args.length < 3) {
                throw new CommandLine.ParameterException(commandLine,
                        "Usage: cr3-cli subband-stats <file.cr3> <seq>");
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
}
