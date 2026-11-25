# CR3 and CRX release provenance

## Review boundary

The source audit reviewed [baseline `1c1195a`](https://github.com/carstenartur/Photographer/tree/1c1195a70ff1e1e7fd0f89e3ec16cd7f65624ed5), the PR records and the committed codec results. It checked the listed TSV digests and totals and inspected the seven Fiji preview JARs. Its experiment findings use the recorded results and reproduction instructions. The later [v0.1.5 verification](#v015-release-verification) identifies the released source and the checked artifact bytes. [Development history](development-history.md)

Release 0.1.5 includes the measured 14-bit CRX V1 lossless and three-level C-RAW reader paths and the experimental four-profile lossless CR3 writer. The Fiji distribution carries both in its libraries. The source and CI links require access to the private repository, and the Fiji update site serves the current JARs publicly. The sections below record the engineering evidence, publication rights and open patent questions. [Release record](../releases/0.1.5.md) · [Implementation status](../cr3/current-state.md) · [Repository publication audit](../public-release.md)

## Claim-to-evidence map

| Claim | Registered sources and promotion | What the evidence covers |
| --- | --- | --- |
| Lossless CRX reads native sensor values in Java | RG4 uses SRC-001, 002, 007–013 as listed individually in the ledger. Issue #336 recorded the reader freezes and PR #338 promoted the implementation in `4255ae07e4cdbd7c35b2b2b4097bdde21a02c5de`. | The normal Java path matched **365,774,016 values** across 11 discovery and four public holdout lossless files. Two C-RAW scope controls returned unsupported at that snapshot and are excluded from the count. The measured profile uses 14-bit samples, four positional planes, one subband per plane and full-height horizontal tiles. [RG4 report](../cr3-results/2026-11-14-crx-native/README.md) · [Issue #336](https://github.com/carstenartur/Photographer/issues/336) · [PR #338](https://github.com/carstenartur/Photographer/pull/338) |
| C-RAW reads reconstructed native values | RG5 cites SRC-002, 003, 007, 010, 011, 013 and 014. Issue #337 froze the two public holdouts. PR #339 promoted the decoder in `f99ef47c065b22e75ff64c77af10870877182e84`. | DNGLab references matched **104,677,712 values** on four public C-RAW files and **41,789,440** on two additional project captures. The separate lossless regression matched 365,774,016 values. The distinct total is **512,241,168 values across 21 files**. For C-RAW these are the native values reconstructed from lossy input. The measured profile is V1, four unrounded 14-bit planes, three wavelet levels and two equal-width horizontal tiles. [RG5 report](../cr3-results/2026-11-14-crx-wavelet/README.md) · [Issue #337](https://github.com/carstenartur/Photographer/issues/337) · [PR #339](https://github.com/carstenartur/Photographer/pull/339) |
| The writer produces interoperable lossless CR3 for four measured modes | PR #365 cites SRC-004, 005, 007, 008, 012 and 013, the project reader and format work. The promotion commit is `895678404bc043b933a923bab7b3fbe62e6c8890`. | Four full synthetic mosaics are exercised through the public ImageIO writer and reader. The PR reports matching DNGLab native checksums on four camera mosaics re-encoded by the writer, covering **104,677,712 values**, plus preview and thumbnail extraction. It also reports DNGLab DNG conversion and LightCraft JPEG rendering on four synthetic outputs. Canon DPP, byte identity to the camera encoder, C-RAW writing and other camera modes remain unverified. [PR #365](https://github.com/carstenartur/Photographer/pull/365) · [Writer construction and validation](../cr3/format.md#lossless-writer-camera-profiles) |

The writer generates tracks, JPEGs and fresh metadata from the selected camera profile and full Bayer raster. The generated metadata identifies Photographer, uses nominal calibration and omits capture-specific date, lens, serial and location fields. Camera captures supply the compatibility fixtures. [ImageIO writer guide](../cr3/imageio.md#write-a-lossless-cr3-using-a-camera-profile) · [Writer source](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/encode/Cr3Writer.java) · [Metadata source](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/encode/Cr3ProfileMetadata.java)

The source register and ledger preserve the project inputs and verdicts, including rejected and partial experiments. D11 extracted a narrow candidate rule from Canon's published patent application US 2016/0323602 A1. D12 fell short of the promotion criteria, and D13 found the context mapping insufficient. RG2 also rejected the proposed run change on the fixed lossless panel. RG4 and RG5 cite the observations within their measured boundaries. [Source register](https://github.com/carstenartur/Photographer/blob/1c1195a70ff1e1e7fd0f89e3ec16cd7f65624ed5/docs/cleanroom/source-register.json) · [Experiment ledger](https://github.com/carstenartur/Photographer/blob/1c1195a70ff1e1e7fd0f89e3ec16cd7f65624ed5/docs/cleanroom/experiment-ledger.json)

The format reference links each implemented area to the layout and observed behavior, including [lossless sample coding](../cr3/format.md#v1-lossless-sample-coding), [three-level C-RAW reconstruction](../cr3/format.md#v1-three-level-c-raw-reconstruction) and [freshly generated writer metadata](../cr3/format.md#generated-metadata). Start the implementation walk-through at the [decoder facade](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/decode/Cr3Decoder.java), the [CRX codec module](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/canon-cr3-codec/README.md) and the writer linked above.

## Contributor record

The [development history](development-history.md#early-sources-and-remaining-evidence) links the public technical references and the April architecture commit.

| Change | Evidence |
| --- | --- |
| Lossless reader, [PR #338](https://github.com/carstenartur/Photographer/pull/338) | Project camera files, published Canon material and recorded comparison results. |
| C-RAW reader, [PR #339](https://github.com/carstenartur/Photographer/pull/339) | Registered technical references and full-sensor black-box comparisons. |
| Format reference and lossless writer, [PR #364](https://github.com/carstenartur/Photographer/pull/364) and [PR #365](https://github.com/carstenartur/Photographer/pull/365) | The PRs record the technical references, a licence lookup and the writer’s tool hashes and per-profile results. A writer-specific ledger entry binding the promotion, executable run and output manifest is still missing. |

## Reproduction and retention

1. Resolve the stable tag to its commit and tree and record the workflow run URL and artifact SHA-256 digests. Use one checkout for the replay and keep generated reports outside the tracked evidence tree. If the tag differs from the source baseline, review changes in the codec, ImageIO, evidence and packaging paths.
2. Run the checkout validators below. They check register and ledger structure, repository references and generated evidence coverage and locks. They do not execute the full codec comparison or authenticate the rights and contributor statements.
3. Follow the RG4 and RG5 replay commands using JDK 25 and the manifest-pinned inputs and expected rasters. The procedures distinguish inputs supplied with the repository, CC0 downloads and the separately prepared first-party C-RAW comparison. Preserve any skipped or unavailable portion in the review record. [RG4 replay](../cr3-results/2026-11-14-crx-native/README.md#reproduction) · [RG5 replay](../cr3-results/2026-11-14-crx-wavelet/README.md#reproduction)
4. Run the encoder and ImageIO writer tests. For independent writer interoperability, replay the four cases in #365 with the pinned DNGLab and LightCraft binaries and preserve the commands, binary digests, per-profile input and output digests, and native and preview results. The round trip alone exercises the project's own reader and writer together. [Writer tests](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/canon-cr3-imageio/src/test/java/com/photographer/focusstack/cr3/imageio/CanonCr3ImageWriterTest.java) · [Independent comparison recorded in #365](https://github.com/carstenartur/Photographer/pull/365)
5. Archive the reviewed, media-free evidence with a stable digest. Check that the exact referenced reports and TSVs are present. The builder at the examined baseline uses top-level result globs that miss dated nested RG4 and RG5 files. The maintained builder follows the registered evidence outputs. Its separate CI workflow has 30-day retention. A release packet needs a durable archive and a report inventory. [Bundle builder](https://github.com/carstenartur/Photographer/blob/9040380f9279de1c45ea962177a63791ea176796/scripts/build_cleanroom_evidence_bundle.py) · [Evidence retention](evidence-retention.md)

```sh
python3 scripts/validate_cleanroom_evidence.py .
python3 scripts/manage_cr3_generated_evidence.py --root . verify
mvn -B -ntp -pl canon-cr3-imageio -am \
  -Dtest=CrxLosslessEncoderTest,CanonCr3ImageWriterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

| Checked object at the source baseline | SHA-256 |
| --- | --- |
| [RG4 full-sensor TSV](../cr3-results/2026-11-14-crx-native/full-sensor.tsv) | `94ada9738a214d19f1f7752761efcd5888cefad90a6a1b65714649f205c8fd8f` |
| [RG5 C-RAW public comparison TSV](../cr3-results/2026-11-14-crx-wavelet/craw-sensors.tsv) | `abfc070e9b885cee48dd40943e3466113a06b7ad186a673eabe2ab1821691513` |
| [RG5 two first-party comparisons TSV](../cr3-results/2026-11-14-crx-wavelet/first-party-craw.tsv) | `277c98a17c076e4386f783fe501a56ab24b668ab3e54437875d94429f4fc327e` |
| [RG5 regression TSV](../cr3-results/2026-11-14-crx-wavelet/full-sensor.tsv) | `0c16de6181258387035dabe99feb1e17fdf1e12c6180a083f3b46fbdb119e085` |
| [Public C-RAW input and reference registry](../../scripts/research/public-crx/craw-sensors.tsv) | `c4dec60d35e2df53be4a0609e38bd625fa6497f4bf7bc30419e6f6940f7515d5` |
| [Public lossless input and reference registry](../../scripts/research/public-crx/lossless-holdout.tsv) | `3ad59a5669f666ec6aca419c76f4ec806616bc956b0a2840e5e3171fccb386ba` |

The RG4 and RG5 ledger digests identify the first and second committed TSVs above. Their run and commit fields are null and the entries describe local execution, the baseline and the retained reports. The promotion commits identify the reviewed code changes. They do not identify a CI run of the pinned scientific comparison. [Ledger fields](schema-notes.md) · [Generated evidence policy](generated-evidence.md)

## Source and fixture distribution

| Material | Recorded basis | Outstanding evidence |
| --- | --- | --- |
| Photographer source code and documentation | The repository has an MIT license and a contributor licensing statement. | Confirm the notice in actual distributed copies and the scope of any third-party content. [LICENSE](../../LICENSE) · [Contribution terms](../../CONTRIBUTING.md) |
| Optical calibration in `cr3-processing` | Three Lensfun tables drive the supported optical stage of normal RAW development; callers can disable it. The tables use CC BY-SA 3.0. | The processing JAR contains the coefficients, license and attribution under `META-INF/lensfun/`. The notice identifies the upstream revision, original XML files and format conversion. [Packaged notice](../../canon-cr3-processing/src/main/resources/META-INF/lensfun/NOTICE.txt) |
| Eight public CR3 holdout and C-RAW inputs | The two input registries record raw.pixls.us file IDs, URLs, input SHA-256, expected PGM SHA-256 and CC0 1.0. | Preserve the per-file rights record. A checksum establishes which bytes were used. It cannot establish the licensor's authority or other parties' rights. [CC0 legal code](https://creativecommons.org/publicdomain/zero/1.0/legalcode) |
| First-party CR3s, previews, TIFF and PNG sidecars | Camera capture and geometry records are in SRC-007/008. The JSON reference manifest records 11 TIFF oracle SHA-256 values with the tool and version. The companion Markdown lists TIFF and PNG outputs with commands, geometry and SHA-256. SRC-009 documents rawpy 0.27.0 / LibRaw 0.22.1. | Tool and digest records establish derivation and integrity. The publication audit lists earlier versions of the fixtures. [Machine-readable manifest](../../canon-cr3-imageio/src/test/resources/samples/reference-manifest.json) · [Reference commands and inventory](../cr3-reference-manifest.md) · [Publication audit](../public-release.md) |
| External oracle software and auxiliary calibration data | The research scripts pin the oracles as external executables. The ImageIO DNG calibration code separately identifies RawTherapee 5.12 DCP profile data. | Review any redistribution of the external binaries independently from use of their test results. The DCP objects and profile declarations are recorded below. [DNG calibration provenance comment](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/canon-cr3-imageio/src/main/java/com/photographer/focusstack/dng/imageio/Cr3DngMetadata.java#L91-L110) |
| Adobe DNG notice | The source and the ImageIO guide packaged by the Maven release display Adobe’s DNG notice. The v0.1.5 Docker build omits the packaged guide. [Release packaging record](../releases/0.1.5.md#artifact-verification) | For distributions using the Adobe patent licence, check the prominent notice in the accompanying documentation, including for Docker. Adobe's licence covers Essential Claims for compliant DNG reads and writes. [DNG notice in source](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/canon-cr3-imageio/src/main/java/com/photographer/focusstack/dng/imageio/DngImageMetadata.java) · [Adobe DNG specification and patent license](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html) |
| Canon documentation | SRC-006 is the CCAPI reference source for camera control. The register expressly excludes it as evidence of CRX semantics. | Keep restricted source documents and SDK material out of public releases and evidence exports. Review citations and rights before distributing any vendor material. [Source register](source-register.json) · [Publication audit](../public-release.md) |

The 15 November audit found original fixture blobs at 42 branch tips and two tags. The [v0.1.4 tree](https://github.com/carstenartur/Photographer/tree/v0.1.4) contains 13 originals and the [v0.1.3 tree](https://github.com/carstenartur/Photographer/tree/v0.1.3) contains eight. An original version also remains in `main`'s ancestry. The historical audit reports the affected files and metadata fields. [Historical-media audit](../public-release.md#verified-scope-on-15-november-2026) · [Publication routes](../public-release.md#sensitive-binary-history)

### DNG calibration data

The two matrices and illuminant identifiers in each embedded DNG calibration entry match the named RawTherapee 5.12 DCP object at upstream commit `a8a3d1bc7b419ed1c974c39276c97e618a4f9a1b`. The values below are the actual `ProfileCopyright` fields from the four objects. This is provenance for auxiliary data in ImageIO, separate from the CRX reader and writer derivation.

| Camera profile | Upstream Git blob ID | Recorded declaration |
| --- | --- | --- |
| PowerShot G5 X Mark II | `d6a0b3b1e9cef98b30d7d176b3932328a8441672` | `public domain` · [DCP](https://github.com/RawTherapee/RawTherapee/blob/a8a3d1bc7b419ed1c974c39276c97e618a4f9a1b/rtdata/dcpprofiles/CANON%20POWERSHOT%20G5%20X%20MARK%20II.dcp) |
| EOS R | `6ea5302e290637ef5fb806575782f07b7d344e80` | `public domain` · [DCP](https://github.com/RawTherapee/RawTherapee/blob/a8a3d1bc7b419ed1c974c39276c97e618a4f9a1b/rtdata/dcpprofiles/Canon%20EOS%20R.dcp) |
| EOS RP | `8379d9703bde2c9c2e9864fbc1899a5aec88771f` | `RawTherapee CC0` · [DCP](https://github.com/RawTherapee/RawTherapee/blob/a8a3d1bc7b419ed1c974c39276c97e618a4f9a1b/rtdata/dcpprofiles/Canon%20EOS%20RP.dcp) |
| EOS M50 | `38e0e78599874c3a68d3353e03465c13b6dbb512` | `public domain` · [DCP](https://github.com/RawTherapee/RawTherapee/blob/a8a3d1bc7b419ed1c974c39276c97e618a4f9a1b/rtdata/dcpprofiles/CANON%20EOS%20M50.dcp) |

## Fiji distribution

The update site installs one sensor-import plugin and six CR3 library JARs. Fiji provides ImageJ and SLF4J. The importer exposes a read menu. The libraries at the examined baseline also build the Canon lossless writer and its ImageIO SPI, so an audit of their distribution must include that writer. The Fiji menu smoke runs on Java 21 and compares a single lossless fixture against a manifest-bound reference. Full-panel C-RAW and writer evidence is recorded separately above. [Fiji packaging workflow](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/.github/workflows/cr3-fiji-distribution.yml) · [Seven-JAR selector](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/scripts/build_cr3_fiji_site.py) · [ImageIO service file](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/canon-cr3-imageio/src/main/resources/META-INF/services/javax.imageio.spi.ImageWriterSpi)

The public preview at Pages commit `665698a1ec436f6c1aabb795eea773984554b4da` has a site-level `LICENSE.txt`. Its seven version `0.1.5-SNAPSHOT` JARs at timestamp `20261115044335` contained no license entry or MIT permission text. Archive entry inspection of those seven JARs found no CR3 fixtures, reference images, manuals or native libraries. Those observations describe the preview bytes only. At the examined source baseline, Maven's distribution profiles activated the license resource inclusion. The Fiji-only profile omitted that resource. The packaging change adds the notice to the new Fiji JARs, executable app JAR and runtime image. Recheck the final stable ZIPs and served JARs directly for complete notices and intended contents. [Pinned preview site](https://github.com/carstenartur/Photographer/tree/665698a1ec436f6c1aabb795eea773984554b4da/fiji/cr3) · [Historical Maven profiles](https://github.com/carstenartur/Photographer/blob/1c1195a70ff1e1e7fd0f89e3ec16cd7f65624ed5/pom.xml) · [Packaging change](https://github.com/carstenartur/Photographer/pull/374) · [Repository license](../../LICENSE)

A release evidence packet records the stable commit and run, both Fiji ZIP hashes, JAR paths and hashes, Maven coordinates and license notice checks, update database digest and the anonymously served current version. Distinguish inherited preview history from the seven current JARs. Check actual package entries for fixtures, vendor files, third-party binaries and unexpected data before making an exclusion claim. [Fiji publication checks](https://github.com/carstenartur/Photographer/blob/56fdcafa0bc6576759ef552d60e85cd418af14f3/scripts/publish_cr3_fiji_site.py) · [Fiji release guide](../cr3/fiji-update-site.md#release-publication)

## v0.1.5 release verification

The annotated tag `v0.1.5` resolves to commit `56fdcafa0bc6576759ef552d60e85cd418af14f3` and tree `68e6e80d4ec614ea628a2df983b85cd8302a51dc`. The changes between the audited baseline and this tag affect packaging, license checks and version metadata. The reader and writer implementations are identical between the two source revisions. [Packaging change](https://github.com/carstenartur/Photographer/pull/374) · [Version handoff](https://github.com/carstenartur/Photographer/pull/376) · [Publication audit](../public-release.md)

The released installation ZIP and public update site contain the same seven current Fiji JARs, all with the project MIT notice. They were checked by timestamp, Maven version and bytes against the release ZIPs. The earlier preview entries remain in the updater history. The executable app JAR and the 18 Maven binary, sources and Javadoc JARs also carry the notice. [Release files and digests](../releases/0.1.5.md#artifact-verification) · [Maven tree](https://github.com/carstenartur/Photographer/tree/9af70e857d3f138b2688f377e135e85b850f0c81)

### Reproduce the dated release evidence archive

Use these commands from a checkout containing the current evidence builder and the release tag.

```sh
git worktree add --detach ../photographer-v0.1.5 v0.1.5
python3 scripts/build_cleanroom_evidence_bundle.py \
  ../photographer-v0.1.5 ../cr3-v0.1.5-evidence.zip
```

The builder introduced in [PR #375](https://github.com/carstenartur/Photographer/pull/375) collected 70 text and table files from the release tree. Each entry matched the release's Git blob. Two exports were byte-identical with SHA-256 `7aa8d5cfc2ece4b5d33483b32a508f3fbbc12387b90b3e72af7ebf712e0c0d21`. This reviewer guide accompanies that dated archive. [Evidence retention](evidence-retention.md)

## Copyright and access conditions

In a German or EU review, distinguish the protected expression of a program from its functionality and the file formats used to exercise it. Section 69d(3) UrhG addresses observation, study and testing during acts the user is entitled to perform. Section 69e sets specific conditions for decompilation for interoperability. Review lawful access, agreements and rights to redistribute protected text, code and other assets for each source. German trade secret law also sets conditions on acquiring a secret through observation or testing. [§§ 69a, 69d, 69e and 69g UrhG](https://www.gesetze-im-internet.de/urhg/BJNR012730965.html) · [Directive 2009/24/EC](https://eur-lex.europa.eu/eli/dir/2009/24/oj) · [CJEU C-406/10, SAS Institute](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62010CJ0406) · [§§ 3 and 4 GeschGehG](https://www.gesetze-im-internet.de/geschgehg/)

For this release, review the [early documentation references](development-history.md#early-sources-and-remaining-evidence), the later PR disclosures, and the fixture and asset inventory above. Record which descriptions and evidence a change used and any prior access to third-party source code. Check Canon SDK and CCAPI materials against the applicable download and licence terms before any redistribution. [Canon developer programme](https://www.usa.canon.com/support/sdk) · [Canon USA terms of use](https://www.usa.canon.com/terms-of-use)

## Patent review record

| Registered application | Experiment | Grant documents for the review |
| --- | --- | --- |
| SRC-001 · US 2016/0323602 A1 | Candidate Golomb–Rice code length and parameter update in D11 and D12 | [US 9,942,569 B2](https://patentimages.storage.googleapis.com/00/ab/23/17c0570cfee93e/US9942569.pdf) · [JP 6512927 B2](https://patentimages.storage.googleapis.com/38/01/ea/d85d463d3239f4/JP6512927B2.pdf) |
| SRC-010 · JP 2017-192077 A | Candidate low-frequency run decision in RG2 | [JP 6727010 B2](https://patentimages.storage.googleapis.com/7c/29/53/386f5f736a507d/JP6727010B2.pdf) |

The grant documents identify claims to review. Current status, additional family members, jurisdiction-specific applicability and a claim map to the released decoder and encoder remain open. [Technical patent references](../cr3/format.md#patent-publications) · [European Patent Register](https://www.epo.org/en/searching-for-patents/legal/register) · [German Patent Act §§ 9 and 14](https://www.gesetze-im-internet.de/patg/) · [U.S. statutory text](https://www.govinfo.gov/content/pkg/USCODE-2024-title35/html/USCODE-2024-title35-partIII-chap28-sec271.htm)

For each market under review, record the family and priority, the local application and grant identifiers, the status date and the applicable granted claims. Map those claims to the named reader and writer paths at the audited tag, and record the distribution and use scenarios considered. Keep the signed or dated finding separate from the engineering experiment report. [EPO patent families](https://www.epo.org/en/searching-for-patents/helpful-resources/first-time-here/patent-families) · [DPMA register and research](https://www.dpma.de/recherchieren/schutzrechte-recherchieren/patente-recherchieren)
