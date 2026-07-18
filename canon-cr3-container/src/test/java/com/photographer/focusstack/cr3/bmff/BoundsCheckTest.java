package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.Cr3FormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link BoundsCheck}.
 */
class BoundsCheckTest {

    @TempDir
    Path tempDir;

    /**
     * Creates an ImageInputStream backed by a temporary file with the given size.
     * Unlike MemoryCacheImageInputStream, FileImageInputStream correctly reports length().
     */
    private ImageInputStream createStream(int size) throws IOException {
        Path file = tempDir.resolve("test_" + size + ".bin");
        Files.write(file, new byte[size]);
        return ImageIO.createImageInputStream(file.toFile());
    }

    @Nested
    @DisplayName("validateOffset")
    class ValidateOffset {

        @Test
        @DisplayName("Should accept offset 0 for non-empty stream")
        void acceptZeroOffset() throws IOException {
            try (var input = createStream(100)) {
                assertThatCode(() -> BoundsCheck.validateOffset(input, 0, "test"))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("Should accept offset at stream end")
        void acceptOffsetAtEnd() throws IOException {
            try (var input = createStream(100)) {
                assertThatCode(() -> BoundsCheck.validateOffset(input, 100, "test"))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("Should reject negative offset")
        void rejectNegativeOffset() throws IOException {
            try (var input = createStream(100)) {
                assertThatThrownBy(() -> BoundsCheck.validateOffset(input, -1, "test"))
                        .isInstanceOf(Cr3FormatException.class)
                        .hasMessageContaining("Negative offset");
            }
        }

        @Test
        @DisplayName("Should reject offset beyond stream length")
        void rejectBeyondStreamLength() throws IOException {
            try (var input = createStream(100)) {
                assertThatThrownBy(() -> BoundsCheck.validateOffset(input, 101, "test"))
                        .isInstanceOf(Cr3FormatException.class)
                        .hasMessageContaining("exceeds stream length");
            }
        }
    }

    @Nested
    @DisplayName("validateRange")
    class ValidateRange {

        @Test
        @DisplayName("Should accept valid range within stream")
        void acceptValidRange() throws IOException {
            try (var input = createStream(100)) {
                assertThatCode(() -> BoundsCheck.validateRange(input, 10, 50, "test"))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("Should accept range exactly covering the stream")
        void acceptExactRange() throws IOException {
            try (var input = createStream(100)) {
                assertThatCode(() -> BoundsCheck.validateRange(input, 0, 100, "test"))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("Should reject range exceeding stream")
        void rejectExceedingRange() throws IOException {
            try (var input = createStream(100)) {
                assertThatThrownBy(() -> BoundsCheck.validateRange(input, 50, 60, "test"))
                        .isInstanceOf(Cr3FormatException.class)
                        .hasMessageContaining("exceeds stream length");
            }
        }

        @Test
        @DisplayName("Should reject negative size")
        void rejectNegativeSize() throws IOException {
            try (var input = createStream(100)) {
                assertThatThrownBy(() -> BoundsCheck.validateRange(input, 0, -5, "test"))
                        .isInstanceOf(Cr3FormatException.class)
                        .hasMessageContaining("Negative size");
            }
        }

        @Test
        @DisplayName("Should reject negative offset in range")
        void rejectNegativeOffsetInRange() throws IOException {
            try (var input = createStream(100)) {
                assertThatThrownBy(() -> BoundsCheck.validateRange(input, -1, 10, "test"))
                        .isInstanceOf(Cr3FormatException.class)
                        .hasMessageContaining("Negative offset");
            }
        }
    }

    @Nested
    @DisplayName("validateBox")
    class ValidateBox {

        @Test
        @DisplayName("Should accept box within stream bounds")
        void acceptValidBox() throws IOException {
            try (var input = createStream(200)) {
                IsoBox box = new IsoBox("test", 100, 50);
                assertThatCode(() -> BoundsCheck.validateBox(box, input))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("Should reject box exceeding stream")
        void rejectOversizedBox() throws IOException {
            try (var input = createStream(100)) {
                IsoBox box = new IsoBox("test", 200, 0);
                assertThatThrownBy(() -> BoundsCheck.validateBox(box, input))
                        .isInstanceOf(Cr3FormatException.class)
                        .hasMessageContaining("exceeds stream length");
            }
        }

        @Test
        @DisplayName("Should accept box with size 0 (extends to EOF)")
        void acceptSizeZeroBox() throws IOException {
            try (var input = createStream(100)) {
                IsoBox box = new IsoBox("last", 0, 50);
                assertThatCode(() -> BoundsCheck.validateBox(box, input))
                        .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("validateChildBounds")
    class ValidateChildBounds {

        @Test
        @DisplayName("Should accept child within parent bounds")
        void acceptValidChild() {
            IsoBox child = new IsoBox("chld", 20, 100);
            assertThatCode(() -> BoundsCheck.validateChildBounds(child, 200))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reject child exceeding parent")
        void rejectChildExceedingParent() {
            IsoBox child = new IsoBox("chld", 50, 80);
            assertThatThrownBy(() -> BoundsCheck.validateChildBounds(child, 100))
                    .isInstanceOf(Cr3FormatException.class)
                    .hasMessageContaining("exceeds parent boundary");
        }

        @Test
        @DisplayName("Should accept child exactly fitting parent")
        void acceptChildExactlyFitting() {
            IsoBox child = new IsoBox("chld", 20, 80);
            assertThatCode(() -> BoundsCheck.validateChildBounds(child, 100))
                    .doesNotThrowAnyException();
        }
    }
}