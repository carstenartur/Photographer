# CR3 Reference Manifest

This manifest is the provenance gate for CR3 reference sidecars used by residual diagnostics.

- Scope: `canon-cr3-imageio/src/test/resources/samples/**/*.reference.tiff` and `**/*.reference.png`
- Gate: `ReferenceManifestValidationIT`
- Policy: Reference-comparison output is only evidence-grade when this manifest validates.

## Provenance records

<!-- reference-manifest-tsv:start -->
```text
fixture|sidecar|tool|version|command|sha256|width|height|bitDepth|referenceType|geometryMatchesCrxBig
sample.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture sample.cr3 --type tiff|83a2a0f236c4dd0b282901ec7b91386751314a5b8aee4e5f5c15d378b7d47ff1|5632|3710|16|RAW_LINEAR|true
sample.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture sample.cr3 --type png|28ff78a46a52ca45970a46afd59fc0e11ad1ae3d06d7518ef77f793e4dda5f7a|1620|1080|8|PREVIEW_RENDERED|false
sample-iso-high.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture sample-iso-high.cr3 --type tiff|e8c5560962fee2f0d8753e3729b6340077b2602992a43b2ef629d80acb4acba9|5632|3710|16|RAW_LINEAR|true
sample-iso-high.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture sample-iso-high.cr3 --type png|70adfb66ba055820e7e02730592855734eaf21eb3ecb2f451027d48148878240|1620|1080|8|PREVIEW_RENDERED|false
sample-tele.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture sample-tele.cr3 --type tiff|87d59273c96b1ac4274a8614fba8246841c4d180f8cbd8ebc5770e9bbd1ac3f9|5632|3710|16|RAW_LINEAR|true
sample-tele.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture sample-tele.cr3 --type png|724430a19f96342607269803e7e10e3e07d358c9417ef887b157ce7f6571c2e5|1620|1080|8|PREVIEW_RENDERED|false
sample-wide.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture sample-wide.cr3 --type tiff|e826166997a0cbb7facf034f8e92a0dbffabf204671bb4b930b3e10711341a86|5632|3710|16|RAW_LINEAR|true
sample-wide.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture sample-wide.cr3 --type png|1b85a61b60b7ceb889f11d4550a4c4ba0c5e4ad65a29e16f9a2e78d49bce74b2|1620|1080|8|PREVIEW_RENDERED|false
sample-wb-tungsten.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture sample-wb-tungsten.cr3 --type tiff|a50b322018ea032be17d8764aa305ded2ce7adcff6df8dd69296daa1f999b64f|5632|3710|16|RAW_LINEAR|true
sample-wb-tungsten.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture sample-wb-tungsten.cr3 --type png|c90c40f49604252d67b3d32ca3141ff0fa7cff5b68dd41302fdf6b764f0efc7a|1620|1080|8|PREVIEW_RENDERED|false
eosr-iso-low.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture eosr-iso-low.cr3 --type tiff|3066705425f17355aea06d63d67582348d7b7fc49ba67d4f60440c113b08b716|6888|4546|16|RAW_LINEAR|true
eosr-iso-low.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture eosr-iso-low.cr3 --type png|dd532513dfe482509237436b113dea343dd3325791ec7ab35f2407c64ff976d8|1620|1080|8|PREVIEW_RENDERED|false
eosr-iso-mid.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture eosr-iso-mid.cr3 --type tiff|9714724d63c5315cda3fd85a835c0f58082862f6e113839dd41d5e5df4e2a974|6888|4546|16|RAW_LINEAR|true
eosr-iso-mid.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture eosr-iso-mid.cr3 --type png|8d3cb2cbdea17177c5fddb026003a16c70068ac31e2cb9e2043ecd7003df8a3c|1620|1080|8|PREVIEW_RENDERED|false
eosr-iso-high.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture eosr-iso-high.cr3 --type tiff|675143adff7da6ca7ef4d24a43a310a427676544c742484f3b9ad6b94b9fcf0f|6888|4546|16|RAW_LINEAR|true
eosr-iso-high.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture eosr-iso-high.cr3 --type png|52c5a9c84261787c5e319f9d2921763e4a558bc7277924eaf247949cce6f3272|1620|1080|8|PREVIEW_RENDERED|false
research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3 --type tiff|c0f8bab61851af949388a81009643032281631989d5f41cf7a91f74fd16b6c7a|5632|3710|16|RAW_LINEAR|true
research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3 --type png|01a0e6d70fae85a409aa117afc9348ddf18f5b0d9ae6fea46a13d0d190d18084|1620|1080|8|PREVIEW_RENDERED|false
research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3 --type tiff|914af22b37e1180ff93121cd775f9bb2d9299e4d536014e27edb9a5542f77972|5632|3710|16|RAW_LINEAR|true
research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3 --type png|2a12d9a242ccf5046f37bd6eef9409e158461023f17832af2da14e7322cd96ba|1620|1080|8|PREVIEW_RENDERED|false
research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3|reference.tiff|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3 --type tiff|00650bb16863bc9738ae4481dc2053d2ce59ea1597b06d8b67dd931a51cff6f0|5632|3710|16|RAW_LINEAR|true
research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3|reference.png|LibRaw|rawpy 0.27.0 / LibRaw 0.22.1|python tools/reference-export/export_references.py --fixture research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3 --type png|46f3c9b888ede2850e19e0c757f9aa5a792b079a29acb03344a66812ab5a0be7|1620|1080|8|PREVIEW_RENDERED|false
```
<!-- reference-manifest-tsv:end -->

## Notes

- `reference.tiff` sidecars (`referenceType=RAW_LINEAR`) are full-sensor Bayer mosaics and must match
  CRX_BIG geometry exactly.  Exported by `export_references.py --type tiff`.
- `reference.png` sidecars (`referenceType=PREVIEW_RENDERED`) are rendered/preview artifacts, not
  RAW-linear evidence.  Exported by `export_references.py --type png`.
- To regenerate all sidecars and update this manifest:
  ```
  pip install -r tools/reference-export/requirements.txt
  python tools/reference-export/export_references.py
  python tools/reference-export/generate_manifest.py
  ```
- SHA-256 hashes were locked with rawpy 0.27.0 / LibRaw 0.22.1.  A different library version may
  produce different raw pixel values; re-run `generate_manifest.py` to update the hashes.
- Any sidecar/hash/metadata drift must update this manifest and pass `ReferenceManifestValidationIT`
  before reference-based diagnostics are accepted.
