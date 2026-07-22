package com.photographer.focusstack.cr3.reference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceFixtureResolverTest {

    @Test
    void discoversFixturesInStableRelativeOrderAndClassifiesSidecars(
            @TempDir Path samples) throws Exception {
        Path nested = Files.createDirectories(samples.resolve("nested"));
        create(samples.resolve("z.CR3"));
        create(nested.resolve("a.cr3"));
        create(nested.resolve("a.reference.tiff"));
        create(nested.resolve("a.reference.png"));
        create(samples.resolve("z.reference.tiff"));
        create(samples.resolve("not-a-fixture.txt"));

        ReferenceFixtureResolver resolver =
                new ReferenceFixtureResolver(samples);
        List<ReferenceFixtureResolver.ResolvedFixture> fixtures =
                resolver.discover();

        assertThat(fixtures)
                .extracting(ReferenceFixtureResolver.ResolvedFixture::relativeName)
                .containsExactly("nested/a.cr3", "z.CR3");
        assertThat(fixtures.get(0).hasAllSidecars()).isTrue();
        assertThat(fixtures.get(1).missingSidecars())
                .containsExactly(ReferenceFixtureResolver.MissingSidecar.PNG);
        assertThat(resolver.discoverComplete())
                .extracting(ReferenceFixtureResolver.ResolvedFixture::relativeName)
                .containsExactly("nested/a.cr3");
        assertThat(resolver.incompleteFixtures())
                .extracting(ReferenceFixtureResolver.ResolvedFixture::relativeName)
                .containsExactly("z.CR3");
    }

    @Test
    void requiredSidecarFailureIsTypedAndUsesOnlyRelativeFixtureName(
            @TempDir Path samples) throws Exception {
        Path privateParent = Files.createDirectories(
                samples.resolve("private-host-segment"));
        Path fixture = privateParent.resolve("sample.cr3");
        create(fixture);
        ReferenceFixtureResolver.ResolvedFixture resolved =
                new ReferenceFixtureResolver(samples).resolve(fixture);

        assertThatThrownBy(resolved::requiredTiff)
                .isInstanceOfSatisfying(
                        ReferenceFixtureResolver.MissingReferenceSidecarException.class,
                        failure -> {
                            assertThat(failure.sidecar())
                                    .isEqualTo(ReferenceFixtureResolver.MissingSidecar.TIFF);
                            assertThat(failure.relativeFixture())
                                    .isEqualTo("private-host-segment/sample.cr3");
                            assertThat(failure.getMessage())
                                    .isEqualTo("Missing tiff reference sidecar for private-host-segment/sample.cr3")
                                    .doesNotContain(samples.toAbsolutePath().toString());
                            assertThat(failure).hasNoCause();
                        });
    }

    @Test
    void returnsEmptyForMissingSamplesDirectory(@TempDir Path root)
            throws Exception {
        ReferenceFixtureResolver resolver =
                new ReferenceFixtureResolver(root.resolve("missing"));

        assertThat(resolver.discover()).isEmpty();
        assertThat(resolver.discoverComplete()).isEmpty();
        assertThat(resolver.incompleteFixtures()).isEmpty();
    }

    @Test
    void rejectsFixturesOutsideRootNonCr3AndMissingFiles(@TempDir Path root)
            throws Exception {
        Path samples = Files.createDirectories(root.resolve("samples"));
        Path outside = root.resolve("outside.cr3");
        Path text = samples.resolve("sample.txt");
        create(outside);
        create(text);
        ReferenceFixtureResolver resolver =
                new ReferenceFixtureResolver(samples);

        assertThatThrownBy(() -> resolver.resolve(outside))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below the configured samples root");
        assertThatThrownBy(() -> resolver.resolve(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".cr3 extension");
        assertThatThrownBy(() -> resolver.resolve(samples.resolve("missing.cr3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular non-symbolic-link file")
                .hasNoCause();
    }

    @Test
    void rejectsFinalAndIntermediateSymlinkEscapeWithoutLeakingHostPath(
            @TempDir Path root) throws Exception {
        Path samples = Files.createDirectories(root.resolve("samples"));
        Path outsideDirectory = Files.createDirectories(root.resolve("private-outside"));
        Path outsideFixture = outsideDirectory.resolve("outside.cr3");
        create(outsideFixture);
        ReferenceFixtureResolver resolver = new ReferenceFixtureResolver(samples);

        Path finalLink = samples.resolve("final-link.cr3");
        Path directoryLink = samples.resolve("linked-directory");
        try {
            Files.createSymbolicLink(finalLink, outsideFixture);
            Files.createSymbolicLink(directoryLink, outsideDirectory);
        } catch (UnsupportedOperationException | SecurityException failure) {
            return;
        }

        assertThatThrownBy(() -> resolver.resolve(finalLink))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular non-symbolic-link file")
                .hasMessageNotContaining(root.toString())
                .hasNoCause();
        assertThatThrownBy(() -> resolver.resolve(directoryLink.resolve("outside.cr3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("physically below")
                .hasMessageNotContaining(root.toString())
                .hasNoCause();
        assertThat(resolver.discover()).isEmpty();
    }

    @Test
    void symbolicSidecarsAreClassifiedAsMissing(@TempDir Path root) throws Exception {
        Path samples = Files.createDirectories(root.resolve("samples"));
        Path fixture = samples.resolve("sample.cr3");
        create(fixture);
        Path outside = root.resolve("outside.tiff");
        create(outside);
        try {
            Files.createSymbolicLink(samples.resolve("sample.reference.tiff"), outside);
        } catch (UnsupportedOperationException | SecurityException failure) {
            return;
        }

        ReferenceFixtureResolver.ResolvedFixture resolved =
                new ReferenceFixtureResolver(samples).resolve(fixture);

        assertThat(resolved.missingSidecars())
                .containsExactlyInAnyOrder(
                        ReferenceFixtureResolver.MissingSidecar.TIFF,
                        ReferenceFixtureResolver.MissingSidecar.PNG);
    }

    @Test
    void resolvedMissingSetIsImmutable(@TempDir Path samples) throws Exception {
        Path fixture = samples.resolve("sample.cr3");
        create(fixture);
        ReferenceFixtureResolver.ResolvedFixture resolved =
                new ReferenceFixtureResolver(samples).resolve(fixture);

        assertThatThrownBy(() -> resolved.missingSidecars().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void create(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[]{1});
    }
}
