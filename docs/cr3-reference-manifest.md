# CR3 Reference Manifest

This manifest is the provenance gate for CR3 reference sidecars used by residual diagnostics.

- Scope: `canon-cr3-imageio/src/test/resources/samples/**/*.reference.tiff|png`
- Gate: `ReferenceManifestValidationIT`
- Policy: Reference-comparison output is only evidence-grade when this manifest validates.

## Provenance records

<!-- reference-manifest-tsv:start -->
```tsv
fixtures/sidecartoolversioncommandsha256widthheightbitDepthrawLineargeometryMatchesCrxBig
sample.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)83a2a0f236c4dd0b282901ec7b91386751314a5b8aee4e5f5c15d378b7d47ff15632371016truetrue
sample.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)28ff78a46a52ca45970a46afd59fc0e11ad1ae3d06d7518ef77f793e4dda5f7a162010808falsefalse
sample-iso-high.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)e8c5560962fee2f0d8753e3729b6340077b2602992a43b2ef629d80acb4acba95632371016truetrue
sample-iso-high.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)70adfb66ba055820e7e02730592855734eaf21eb3ecb2f451027d48148878240162010808falsefalse
sample-tele.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)87d59273c96b1ac4274a8614fba8246841c4d180f8cbd8ebc5770e9bbd1ac3f95632371016truetrue
sample-tele.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)724430a19f96342607269803e7e10e3e07d358c9417ef887b157ce7f6571c2e5162010808falsefalse
sample-wide.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)e826166997a0cbb7facf034f8e92a0dbffabf204671bb4b930b3e10711341a865632371016truetrue
sample-wide.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)1b85a61b60b7ceb889f11d4550a4c4ba0c5e4ad65a29e16f9a2e78d49bce74b2162010808falsefalse
sample-wb-tungsten.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)a50b322018ea032be17d8764aa305ded2ce7adcff6df8dd69296daa1f999b64f5632371016truetrue
sample-wb-tungsten.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)c90c40f49604252d67b3d32ca3141ff0fa7cff5b68dd41302fdf6b764f0efc7a162010808falsefalse
eosr-iso-low.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)3066705425f17355aea06d63d67582348d7b7fc49ba67d4f60440c113b08b7166888454616truetrue
eosr-iso-low.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)dd532513dfe482509237436b113dea343dd3325791ec7ab35f2407c64ff976d8162010808falsefalse
eosr-iso-mid.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)9714724d63c5315cda3fd85a835c0f58082862f6e113839dd41d5e5df4e2a9746888454616truetrue
eosr-iso-mid.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)8d3cb2cbdea17177c5fddb026003a16c70068ac31e2cb9e2043ecd7003df8a3c162010808falsefalse
eosr-iso-high.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)675143adff7da6ca7ef4d24a43a310a427676544c742484f3b9ad6b94b9fcf0f6888454616truetrue
eosr-iso-high.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)52c5a9c84261787c5e319f9d2921763e4a558bc7277924eaf247949cce6f3272162010808falsefalse
research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)c0f8bab61851af949388a81009643032281631989d5f41cf7a91f74fd16b6c7a5632371016truetrue
research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)01a0e6d70fae85a409aa117afc9348ddf18f5b0d9ae6fea46a13d0d190d18084162010808falsefalse
research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)914af22b37e1180ff93121cd775f9bb2d9299e4d536014e27edb9a5542f779725632371016truetrue
research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)2a12d9a242ccf5046f37bd6eef9409e158461023f17832af2da14e7322cd96ba162010808falsefalse
research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3reference.tiffLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)00650bb16863bc9738ae4481dc2053d2ce59ea1597b06d8b67dd931a51cff6f05632371016truetrue
research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3reference.pngLibRawrawpy 0.27.0 / LibRaw 0.22.1PR77 rawpy export pipeline (exact CLI not preserved in repo)46f3c9b888ede2850e19e0c757f9aa5a792b079a29acb03344a66812ab5a0be7162010808falsefalse
```
<!-- reference-manifest-tsv:end -->

## Notes

- `reference.tiff` sidecars are expected to be RAW-linear full-sensor mosaics and must match CRX_BIG geometry exactly.
- `reference.png` sidecars are treated as rendered/preview artifacts, not RAW-linear evidence.
- Any sidecar/hash/metadata drift must update this manifest and pass `ReferenceManifestValidationIT` before reference-based diagnostics are accepted.
