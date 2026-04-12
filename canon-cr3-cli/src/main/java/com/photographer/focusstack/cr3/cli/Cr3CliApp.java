package com.photographer.focusstack.cr3.cli;

import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.bmff.IsoBmffReader;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
     * Dumps the raw ISO-BMFF box tree for any BMFF file (does not require CR3 validation).
     * Shows all boxes with offset, length, and type in a hierarchical tree format.
     *
     * @param filePath path to the BMFF/CR3 file
     * @throws Exception if an I/O error occurs
     */
    static void dumpBoxTree(String filePath) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(new File(filePath))) {
            if (input == null) {
                throw new IOException("Cannot open file: " + filePath);
            }
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

    private static void printUsage() {
        System.out.println("Canon CR3 Debug CLI");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  cr3-cli info          <file.cr3>              Show file info and metadata");
        System.out.println("  cr3-cli boxes         <file.cr3>              Dump box structure (CR3 only)");
        System.out.println("  cr3-cli dump-box-tree <file.cr3>              Dump all ISO-BMFF boxes with offset/size");
        System.out.println("  cr3-cli preview       <file.cr3> [output.jpg] Extract JPEG preview");
        System.out.println("  cr3-cli thumb         <file.cr3> [output.jpg] Extract JPEG thumbnail");
    }
}
