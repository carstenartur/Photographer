package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

final class KAdaptationPanelDecode {
    @FunctionalInterface
    interface PlaneDecoder {
        PlaneResult decode(byte[] payload, int width, int height, int bitDepth);
    }

    record PlaneResult(int[] pixels, int symbolsDecoded, boolean exhausted) {}
    record ImageResult(int[] pixels, double completion, boolean exhausted) {}

    private KAdaptationPanelDecode() {}

    static ImageResult decode(Path fixture, int imageWidth, int imageHeight,
                              PlaneDecoder decoder) throws Exception {
        List<SubbandExtractor.SubbandRef> refs = SubbandExtractor.list(fixture.toFile()).stream()
                .filter(ref -> ref.waveletLevels() == 0 && ref.subbandIndex() == 0)
                .sorted(Comparator.comparingInt(SubbandExtractor.SubbandRef::tileSequence)
                        .thenComparingInt(SubbandExtractor.SubbandRef::planeIndex))
                .toList();
        assertThat(refs).as("lossless subbands for %s", fixture).isNotEmpty();

        Map<Integer, List<SubbandExtractor.SubbandRef>> tiles = refs.stream()
                .collect(Collectors.groupingBy(SubbandExtractor.SubbandRef::tileSequence,
                        TreeMap::new, Collectors.toList()));
        int planeWidth = (imageWidth + 1) / 2;
        int planeHeight = (imageHeight + 1) / 2;
        int[][] planes = new int[4][planeWidth * planeHeight];
        int xOffset = 0;
        int tileOrdinal = 0;
        long symbols = 0;
        long capacity = 0;
        boolean exhausted = false;

        for (List<SubbandExtractor.SubbandRef> sourceTile : tiles.values()) {
            List<SubbandExtractor.SubbandRef> tile = new ArrayList<>(sourceTile);
            tile.sort(Comparator.comparingInt(SubbandExtractor.SubbandRef::planeIndex));
            assertThat(tile).hasSize(4);
            int remaining = planeWidth - xOffset;
            int width = tileOrdinal == tiles.size() - 1
                    ? remaining : Math.min(tile.getFirst().planeWidth(), remaining);
            int height = Math.min(tile.getFirst().planeHeight(), planeHeight);

            for (SubbandExtractor.SubbandRef ref : tile) {
                PlaneResult result = decoder.decode(
                        SubbandExtractor.read(fixture.toFile(), ref),
                        width, height, ref.bitDepth());
                for (int y = 0; y < height; y++) {
                    System.arraycopy(result.pixels(), y * width,
                            planes[ref.planeIndex()], y * planeWidth + xOffset, width);
                }
                symbols += result.symbolsDecoded();
                capacity += (long) width * height;
                exhausted |= result.exhausted();
            }
            xOffset += width;
            tileOrdinal++;
        }
        assertThat(xOffset).isEqualTo(planeWidth);
        return new ImageResult(interleave(planes, imageWidth, imageHeight,
                        planeWidth, planeHeight),
                capacity == 0 ? 0.0 : (double) symbols / capacity,
                exhausted);
    }

    private static int[] interleave(int[][] planes, int width, int height,
                                    int planeWidth, int planeHeight) {
        int[] output = new int[width * height];
        for (int py = 0; py < planeHeight; py++) {
            for (int px = 0; px < planeWidth; px++) {
                int source = py * planeWidth + px;
                int x = px * 2;
                int y = py * 2;
                if (y < height && x < width) {
                    output[y * width + x] = planes[0][source];
                }
                if (y < height && x + 1 < width) {
                    output[y * width + x + 1] = planes[1][source];
                }
                if (y + 1 < height && x < width) {
                    output[(y + 1) * width + x] = planes[2][source];
                }
                if (y + 1 < height && x + 1 < width) {
                    output[(y + 1) * width + x + 1] = planes[3][source];
                }
            }
        }
        return output;
    }
}
