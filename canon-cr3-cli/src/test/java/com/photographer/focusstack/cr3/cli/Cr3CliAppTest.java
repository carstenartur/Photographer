package com.photographer.focusstack.cr3.cli;

import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.bmff.IsoBmffReader;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Cr3CliApp}, including snapshot tests for dump-box-tree.
 */
class Cr3CliAppTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("dump-box-tree output via formatBoxTree")
    class FormatBoxTreeTests {

        @Test
        @DisplayName("formatBoxTree should format leaf box correctly")
        void formatLeafBox() {
            IsoBox box = new IsoBox("ftyp", 24, 0);
            StringBuilder sb = new StringBuilder();
            Cr3CliApp.formatBoxTree(box, 0, sb);
            String result = sb.toString();
            assertThat(result).contains("ftyp");
            assertThat(result).containsPattern("offset=\\s*0");
            assertThat(result).containsPattern("size=\\s*24");
        }

        @Test
        @DisplayName("formatBoxTree should indent children")
        void formatWithChildren() {
            IsoBox child = new IsoBox("trak", 50, 8);
            IsoBox parent = new IsoBox("moov", 100, 0, List.of(child));
            StringBuilder sb = new StringBuilder();
            Cr3CliApp.formatBoxTree(parent, 0, sb);
            String result = sb.toString();
            String[] lines = result.split("\n");
            assertThat(lines).hasSize(2);
            assertThat(lines[0]).startsWith("moov");
            assertThat(lines[1]).startsWith("  trak"); // indented by 2
        }

        @Test
        @DisplayName("formatBoxTree should handle deep nesting")
        void formatDeepNesting() {
            IsoBox leaf = new IsoBox("stsz", 12, 100);
            IsoBox stbl = new IsoBox("stbl", 40, 60, List.of(leaf));
            IsoBox minf = new IsoBox("minf", 60, 40, List.of(stbl));
            IsoBox mdia = new IsoBox("mdia", 80, 20, List.of(minf));
            IsoBox trak = new IsoBox("trak", 100, 8, List.of(mdia));
            IsoBox moov = new IsoBox("moov", 120, 0, List.of(trak));

            StringBuilder sb = new StringBuilder();
            Cr3CliApp.formatBoxTree(moov, 0, sb);
            String result = sb.toString();
            String[] lines = result.split("\n");

            assertThat(lines).hasSize(6);
            assertThat(lines[0]).startsWith("moov");           // depth 0
            assertThat(lines[1]).startsWith("  trak");         // depth 1
            assertThat(lines[2]).startsWith("    mdia");       // depth 2
            assertThat(lines[3]).startsWith("      minf");     // depth 3
            assertThat(lines[4]).startsWith("        stbl");   // depth 4
            assertThat(lines[5]).startsWith("          stsz"); // depth 5
        }
    }

    @Nested
    @DisplayName("dump-box-tree snapshot tests")
    class SnapshotTests {

        @Test
        @DisplayName("Snapshot: expected output for simple box tree")
        void snapshotSimpleTree() {
            IsoBox ftyp = new IsoBox("ftyp", 24, 0);
            IsoBox tkhd = new IsoBox("tkhd", 10, 28);
            IsoBox trak = new IsoBox("trak", 30, 24, List.of(tkhd));
            IsoBox moov = new IsoBox("moov", 50, 24, List.of(trak));
            IsoBox mdat = new IsoBox("mdat", 1000, 74);

            StringBuilder sb = new StringBuilder();
            for (IsoBox box : List.of(ftyp, moov, mdat)) {
                Cr3CliApp.formatBoxTree(box, 0, sb);
            }

            String[] lines = sb.toString().split("\n");
            assertThat(lines).hasSize(5);
            assertThat(lines[0]).matches("ftyp\\s+offset=0\\s+size=24\\s*");
            assertThat(lines[1]).matches("moov\\s+offset=24\\s+size=50\\s*");
            assertThat(lines[2]).matches("\\s+trak\\s+offset=24\\s+size=30\\s*");
            assertThat(lines[3]).matches("\\s+tkhd\\s+offset=28\\s+size=10\\s*");
            assertThat(lines[4]).matches("mdat\\s+offset=74\\s+size=1000\\s*");
        }

        @Test
        @DisplayName("Snapshot: parsed synthetic CR3 box tree")
        void snapshotParsedCr3() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);
            StringBuilder sb = new StringBuilder();
            for (IsoBox box : boxes) {
                Cr3CliApp.formatBoxTree(box, 0, sb);
            }

            String output = sb.toString();

            // Top-level boxes
            assertThat(output).contains("ftyp");
            assertThat(output).contains("moov");
            assertThat(output).contains("mdat");
            // Nested boxes under moov
            assertThat(output).contains("trak");
            assertThat(output).contains("mdia");
            assertThat(output).contains("minf");
            assertThat(output).contains("stbl");
            assertThat(output).contains("stsd");
            assertThat(output).contains("stsz");
            assertThat(output).contains("stco");
            // Each line should have offset= and size= fields
            for (String line : output.split("\n")) {
                if (!line.isBlank()) {
                    assertThat(line).containsPattern("offset=\\s*\\d+");
                    assertThat(line).containsPattern("size=\\s*\\d+");
                }
            }
        }

        @Test
        @DisplayName("Indentation should increase by 2 for each nesting level")
        void indentationDepth() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);
            StringBuilder sb = new StringBuilder();
            for (IsoBox box : boxes) {
                Cr3CliApp.formatBoxTree(box, 0, sb);
            }

            String[] lines = sb.toString().split("\n");
            // ftyp at depth 0
            assertThat(lines[0]).startsWith("ftyp");
            // moov at depth 0
            boolean foundMoov = false;
            boolean foundIndentedTrak = false;
            for (String line : lines) {
                if (line.startsWith("moov")) {
                    foundMoov = true;
                }
                if (line.startsWith("  trak")) {
                    foundIndentedTrak = true;
                }
            }
            assertThat(foundMoov).isTrue();
            assertThat(foundIndentedTrak).isTrue();
        }
    }

    @Nested
    @DisplayName("dumpBoxTree integration with file")
    class DumpBoxTreeFile {

        @Test
        @DisplayName("dumpBoxTree should write output to stdout for a valid CR3 file")
        void dumpBoxTreeFileIntegration() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            Path cr3File = tempDir.resolve("test.cr3");
            Files.write(cr3File, cr3Data);

            // We just verify it doesn't throw — stdout output includes log lines
            // which makes exact matching unreliable. The format is tested via formatBoxTree above.
            Cr3CliApp.dumpBoxTree(cr3File.toString());
        }
    }
}
