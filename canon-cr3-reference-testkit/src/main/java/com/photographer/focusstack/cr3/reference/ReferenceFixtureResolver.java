package com.photographer.focusstack.cr3.reference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Deterministically resolves CR3 fixtures and their committed TIFF/PNG sidecars.
 *
 * <p>The resolver performs no image decoding and has no dependency on the CR3
 * production modules. Missing reference artifacts are represented as typed data
 * instead of being hidden by assumptions or path-specific exceptions.</p>
 */
public final class ReferenceFixtureResolver {

    public enum MissingSidecar {
        TIFF,
        PNG
    }

    public record ResolvedFixture(
            String relativeName,
            Path cr3,
            Path tiff,
            Path png,
            Set<MissingSidecar> missingSidecars) {

        public ResolvedFixture {
            Objects.requireNonNull(relativeName, "relativeName");
            Objects.requireNonNull(cr3, "cr3");
            Objects.requireNonNull(tiff, "tiff");
            Objects.requireNonNull(png, "png");
            Objects.requireNonNull(missingSidecars, "missingSidecars");
            missingSidecars = Set.copyOf(missingSidecars);
        }

        @Override
        public Set<MissingSidecar> missingSidecars() {
            return missingSidecars;
        }

        public boolean hasAllSidecars() {
            return missingSidecars.isEmpty();
        }

        public Path requiredTiff() throws MissingReferenceSidecarException {
            if (missingSidecars.contains(MissingSidecar.TIFF)) {
                throw new MissingReferenceSidecarException(relativeName, MissingSidecar.TIFF);
            }
            return tiff;
        }

        public Path requiredPng() throws MissingReferenceSidecarException {
            if (missingSidecars.contains(MissingSidecar.PNG)) {
                throw new MissingReferenceSidecarException(relativeName, MissingSidecar.PNG);
            }
            return png;
        }
    }

    /** Checked diagnostic that never embeds an absolute fixture path. */
    public static final class MissingReferenceSidecarException extends IOException {
        private final String relativeFixture;
        private final MissingSidecar sidecar;

        public MissingReferenceSidecarException(
                String relativeFixture,
                MissingSidecar sidecar) {
            super("Missing " + sidecar.name().toLowerCase(Locale.ROOT)
                    + " reference sidecar for " + relativeFixture);
            this.relativeFixture = Objects.requireNonNull(
                    relativeFixture, "relativeFixture");
            this.sidecar = Objects.requireNonNull(sidecar, "sidecar");
        }

        public String relativeFixture() {
            return relativeFixture;
        }

        public MissingSidecar sidecar() {
            return sidecar;
        }
    }

    private final Path samplesRoot;

    public ReferenceFixtureResolver(Path samplesRoot) {
        this.samplesRoot = Objects.requireNonNull(samplesRoot, "samplesRoot")
                .toAbsolutePath().normalize();
    }

    public Path samplesRoot() {
        return samplesRoot;
    }

    /** Discovers all real CR3 files with stable slash-separated relative ordering. */
    public List<ResolvedFixture> discover() throws IOException {
        if (!Files.isDirectory(samplesRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(samplesRoot)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(ReferenceFixtureResolver::isCr3)
                    .map(this::resolve)
                    .sorted(Comparator.comparing(ResolvedFixture::relativeName))
                    .toList();
        }
    }

    public List<ResolvedFixture> discoverComplete() throws IOException {
        return discover().stream()
                .filter(ResolvedFixture::hasAllSidecars)
                .toList();
    }

    public ResolvedFixture resolve(Path fixture) {
        Objects.requireNonNull(fixture, "fixture");
        Path normalized = fixture.toAbsolutePath().normalize();
        if (!normalized.startsWith(samplesRoot)) {
            throw new IllegalArgumentException(
                    "Fixture must be below the configured samples root");
        }
        if (!isCr3(normalized)) {
            throw new IllegalArgumentException("Fixture must use the .cr3 extension");
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Fixture must be a regular non-symbolic-link file");
        }
        requirePhysicalContainment(normalized);

        String filename = normalized.getFileName().toString();
        String stem = filename.substring(0, filename.length() - 4);
        Path tiff = normalized.resolveSibling(stem + ".reference.tiff");
        Path png = normalized.resolveSibling(stem + ".reference.png");
        EnumSet<MissingSidecar> missing = EnumSet.noneOf(MissingSidecar.class);
        if (!Files.isRegularFile(tiff, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(MissingSidecar.TIFF);
        }
        if (!Files.isRegularFile(png, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(MissingSidecar.PNG);
        }
        String relative = samplesRoot.relativize(normalized)
                .toString()
                .replace('\\', '/');
        return new ResolvedFixture(relative, normalized, tiff, png, missing);
    }

    /** Returns a typed summary without resolving or decoding any images. */
    public List<ResolvedFixture> incompleteFixtures() throws IOException {
        List<ResolvedFixture> incomplete = new ArrayList<>();
        for (ResolvedFixture fixture : discover()) {
            if (!fixture.hasAllSidecars()) {
                incomplete.add(fixture);
            }
        }
        return List.copyOf(incomplete);
    }

    private void requirePhysicalContainment(Path fixture) {
        try {
            Path physicalRoot = samplesRoot.toRealPath();
            Path physicalFixture = fixture.toRealPath();
            if (!physicalFixture.startsWith(physicalRoot)) {
                throw new IllegalArgumentException(
                        "Fixture must remain physically below the configured samples root");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Fixture path could not be resolved safely", failure);
        }
    }

    private static boolean isCr3(Path path) {
        return path.getFileName().toString()
                .toLowerCase(Locale.ROOT)
                .endsWith(".cr3");
    }
}
