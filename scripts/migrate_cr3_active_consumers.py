from pathlib import Path


def replace_once(path_name: str, old: str, new: str) -> None:
    path = Path(path_name)
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{path_name}: expected one anchor, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new))


def replace_between(
        path_name: str,
        start_marker: str,
        end_marker: str,
        replacement: str) -> None:
    path = Path(path_name)
    text = path.read_text()
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    path.write_text(text[:start] + replacement + text[end:])


comparison = (
    "canon-cr3-research/src/test/java/com/photographer/focusstack/cr3/research/"
    "Cr3ReferenceComparisonIT.java"
)
replace_once(
    comparison,
    "import com.photographer.focusstack.cr3.reference.SpatialReferenceMetrics;",
    "import com.photographer.focusstack.cr3.reference.SpatialReferenceMetrics;\n"
    "import com.photographer.focusstack.cr3.reference.ReferenceFixtureResolver;",
)
replace_once(
    comparison,
    '    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");',
    '    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");\n'
    '    private static final ReferenceFixtureResolver FIXTURES =\n'
    '            new ReferenceFixtureResolver(SAMPLES_DIR);',
)
replace_between(
    comparison,
    "    private static List<Path> discoverFixtures() throws IOException {",
    "    private static boolean isLosslessFixture",
    """    private static List<Path> discoverFixtures() throws IOException {
        return FIXTURES.discoverComplete().stream()
                .map(ReferenceFixtureResolver.ResolvedFixture::cr3)
                .filter(Cr3ReferenceComparisonIT::isLosslessFixture)
                .toList();
    }

""",
)
replace_once(
    comparison,
    "        ReferenceImage reference = ReferenceImageLoader.load(referenceTiffPath(fixture));",
    "        ReferenceImage reference = ReferenceImageLoader.load(\n"
    "                FIXTURES.resolve(fixture).requiredTiff());",
)
replace_between(
    comparison,
    "    private static Path referenceTiffPath(Path fixture) {",
    "    private static String escapePipes",
    "",
)

pedestal = (
    "canon-cr3-research/src/test/java/com/photographer/focusstack/cr3/research/"
    "PedestalHypothesisValidationIT.java"
)
replace_once(
    pedestal,
    "import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;",
    "import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;\n"
    "import com.photographer.focusstack.cr3.reference.ReferenceFixtureResolver;",
)
replace_once(
    pedestal,
    '    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");',
    '    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");\n'
    '    private static final ReferenceFixtureResolver FIXTURES =\n'
    '            new ReferenceFixtureResolver(SAMPLES_DIR);',
)
replace_once(
    pedestal,
    "        ReferenceImage reference = loadReference(referenceTiffPath(fixture));",
    "        ReferenceImage reference = loadReference(\n"
    "                FIXTURES.resolve(fixture).requiredTiff());",
)
replace_between(
    pedestal,
    "    private static List<Path> discoverFixtures() throws IOException {",
    "    private static boolean isLosslessFixture",
    """    private static List<Path> discoverFixtures() throws IOException {
        return FIXTURES.discoverComplete().stream()
                .map(ReferenceFixtureResolver.ResolvedFixture::cr3)
                .filter(PedestalHypothesisValidationIT::isLosslessFixture)
                .toList();
    }

""",
)
replace_between(
    pedestal,
    "    private static Path referenceTiffPath(Path fixture) {",
    "    private static String escapePipes",
    "",
)

dc = (
    "canon-cr3-research/src/test/java/com/photographer/focusstack/cr3/research/"
    "DcComponentIsolationIT.java"
)
replace_once(
    dc,
    "import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;",
    "import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;\n"
    "import com.photographer.focusstack.cr3.reference.ReferenceFixtureResolver;",
)
replace_once(
    dc,
    '    private static final Path SAMPLES_DIR = Path.of("src/test/resources/samples");',
    '    private static final Path SAMPLES_DIR = Path.of("src/test/resources/samples");\n'
    '    private static final ReferenceFixtureResolver FIXTURES =\n'
    '            new ReferenceFixtureResolver(SAMPLES_DIR);',
)
replace_once(
    dc,
    """            Path refTiff = referenceTiffPath(fixture);
            if (!Files.exists(refTiff)) continue;""",
    """            Path refTiff = FIXTURES.resolve(fixture).requiredTiff();""",
)
replace_between(
    dc,
    "    private static List<Path> discoverFixturesWithReferences() throws IOException {",
    "    // ── Reference TIFF loading",
    """    private static List<Path> discoverFixturesWithReferences() throws IOException {
        return FIXTURES.discover().stream()
                .filter(fixture -> !fixture.missingSidecars().contains(
                        ReferenceFixtureResolver.MissingSidecar.TIFF))
                .map(ReferenceFixtureResolver.ResolvedFixture::cr3)
                .toList();
    }

""",
)
