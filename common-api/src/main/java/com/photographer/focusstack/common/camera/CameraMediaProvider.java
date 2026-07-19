package com.photographer.focusstack.common.camera;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Optional camera media listing, transfer and added-content observation contract.
 *
 * <p>Implementations must keep list and transfer operations bounded. Cancellation is
 * represented by interrupting the calling thread and must not be reported as camera
 * disconnection.</p>
 */
public interface CameraMediaProvider {

    MediaPage listMedia(MediaQuery query);

    MediaChunk downloadMedia(CameraMediaReference reference, long offset, int maxBytes);

    Optional<CameraMediaReference> awaitAddedMedia(EventMode mode, Duration timeout);

    enum EventMode {
        POLLING,
        POLLING_CONTINUATION,
        MONITORING
    }

    /** Bounded camera-side media query. Empty formats means all supported formats. */
    record MediaQuery(int maxItems, int maxPages, Set<String> formats) {

        public MediaQuery {
            if (maxItems < 1 || maxItems > 1_000) {
                throw new IllegalArgumentException("maxItems must be between 1 and 1000");
            }
            if (maxPages < 1 || maxPages > 100) {
                throw new IllegalArgumentException("maxPages must be between 1 and 100");
            }
            formats = normalizedFormats(formats);
        }

        public static MediaQuery bounded(int maxItems) {
            return new MediaQuery(maxItems, Math.max(1, (maxItems + 99) / 100), Set.of());
        }
    }

    /** Bounded listing result. */
    record MediaPage(
            List<CameraMediaReference> items,
            Integer totalAvailable,
            boolean truncated
    ) {
        public MediaPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (totalAvailable != null && totalAvailable < 0) {
                throw new IllegalArgumentException("totalAvailable must not be negative");
            }
        }
    }

    /** One bounded media transfer chunk. */
    record MediaChunk(
            CameraMediaReference reference,
            long offset,
            byte[] data,
            Long totalSize,
            boolean complete
    ) {
        public MediaChunk {
            reference = Objects.requireNonNull(reference, "reference");
            if (offset < 0L) {
                throw new IllegalArgumentException("offset must not be negative");
            }
            data = data == null ? new byte[0] : data.clone();
            if (totalSize != null && totalSize < 0L) {
                throw new IllegalArgumentException("totalSize must not be negative");
            }
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    private static Set<String> normalizedFormats(Set<String> formats) {
        if (formats == null || formats.isEmpty()) {
            return Set.of();
        }
        TreeSet<String> ordered = new TreeSet<>();
        for (String format : formats) {
            if (format != null && !format.isBlank()) {
                String token = format.trim().toUpperCase(Locale.ROOT);
                if (!Arrays.stream(token.toCharArray())
                        .allMatch(character -> Character.isLetterOrDigit(character))) {
                    throw new IllegalArgumentException("formats must contain alphanumeric tokens only");
                }
                ordered.add(token);
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
    }
}
