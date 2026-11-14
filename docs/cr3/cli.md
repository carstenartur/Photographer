# CR3 Command-Line Tool

`canon-cr3-cli` is the user-facing command wrapper around the split CR3 libraries. Its Java implementation classes are internal; the documented command names, arguments and output files are the CLI surface.

## Build

```bash
mvn -B clean package -pl canon-cr3-cli -am -DskipTests -Djava.awt.headless=true
```

Run the examples below from the repository root. Maven assembles the executable JAR with dependencies at `canon-cr3-cli/target/cr3-cli.jar`.

## Stable inspection and extraction commands

These commands do not require complete sensor-RAW reconstruction:

| Command | Purpose |
|---|---|
| `info <file.cr3>` | High-level tracks and basic metadata |
| `boxes <file.cr3>` | Parsed CR3 box hierarchy |
| `dump-box-tree <file.cr3>` | Raw ISO-BMFF offsets and sizes |
| `dump-metadata <file.cr3>` | Extracted camera/EXIF metadata and active area |
| `dump-crop <file.cr3>` | Sensor, active-area and output geometry |
| `dump-raw-layout <file.cr3>` | RAW track compression/layout summary without claiming decode correctness |
| `list-subbands <file.cr3>` | CRX tile/plane/subband inventory |
| `dump-subband <file.cr3> <seq> [outBase]` | Extract one subband payload and metadata sidecar |
| `subband-stats <file.cr3> <seq>` | Byte/bit statistics for an extracted subband |
| `subband-stats --bits <file.bits>` | Statistics for a previously dumped bit payload |
| `preview <file.cr3> [output.jpg]` | Extract embedded JPEG preview |
| `thumb <file.cr3> [output.jpg]` | Extract embedded JPEG thumbnail |

Example:

```bash
java -jar canon-cr3-cli/target/cr3-cli.jar info photo.cr3
java -jar canon-cr3-cli/target/cr3-cli.jar dump-metadata photo.cr3
java -jar canon-cr3-cli/target/cr3-cli.jar preview photo.cr3 preview.jpg
java -jar canon-cr3-cli/target/cr3-cli.jar list-subbands photo.cr3
```

The `preview` and `thumb` commands decode the embedded image and encode the output as JPEG. To preserve the original camera JPEG byte for byte, use the [copy example](import.md#copy-the-cameras-embedded-jpeg-unchanged).

`dump-raw-layout`, `list-subbands` and `dump-subband` expose structural facts and diagnostics. They do not prove that the experimental decoder reconstructs correct sensor samples.

## Experimental decode commands

| Command | Status | Purpose |
|---|---|---|
| `decode <file.cr3> [output.jpg]` | Experimental | Run current RAW→sRGB pipeline |
| `decode-compare <file.cr3> [outBase]` | Experimental | Write decoded RAW plus embedded preview/thumbnail for diagnosis |
| `pedestal-report <file.cr3>` | Experimental diagnostic | Report selected black-level/pedestal source and values |

Do not use a generated JPEG from `decode` as proof of RAW correctness. Compare against fixed `RAW_LINEAR` references and the quantitative gates described in [Research](research.md).

## Output and automation

CLI automation should:

- check the process exit code;
- treat stderr/stdout wording as diagnostic unless a command explicitly documents a machine-readable file;
- avoid parsing presentation text as a stable API;
- use `.bits` and `.meta.json` outputs from `dump-subband` when exact payload/metadata automation is needed;
- record CLI/project revision and input checksum in durable experiments.

## Reference sidecars

Reference export is deliberately not a CLI command in this module. Use the repository's independent black-box reference pipeline:

- `tools/reference-export/export_references.py`;
- `tools/reference-export/generate_manifest.py`;
- [Reference export pipeline](../reference-pipeline.md).

Keeping the reference generator independent prevents the implementation under test from generating its own ground truth.

## Verification

```bash
mvn -B clean verify -pl canon-cr3-cli -am -Djava.awt.headless=true
```

The module tests pin the implemented command table. When commands are added or removed, update the implementation, tests, module README and this chapter together.

## Related documentation

- [CR3 overview](index.md)
- [Decoder and processing](decoder.md)
- [Module README](../../canon-cr3-cli/README.md)
- [API boundaries](../cr3-api-boundaries.md)
