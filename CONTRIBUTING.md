# Contributing to Photographer

Contributions are welcome. The canonical checkout-to-pull-request guide is [docs/developer/contributing.md](docs/developer/contributing.md); the [Developer Manual](docs/developer/index.md) defines the architecture, module ownership, testing and documentation boundaries.

## Before opening a pull request

- Keep the change focused and place it in the smallest owning module.
- Run the smallest relevant tests while developing.
- Run `bash scripts/verify-product.sh` for product changes.
- Run frontend tests, lint and build for frontend changes.
- Update canonical and module-local documentation when a public contract changes.
- Do not commit credentials, private Canon documentation, unredacted camera identifiers or personal image metadata.
- Report vulnerabilities through [SECURITY.md](SECURITY.md), not a public issue.

## Camera and CR3 evidence

Hardware claims must state the exact camera model, firmware and connection mode. Tests without real hardware prove protocol and lifecycle behavior, not universal compatibility.

Changes to CR3/CRX container, entropy, predictor, wavelet, dequantisation or image-assembly semantics must follow the [clean-room engineering record](docs/cleanroom/README.md) and include the required [contributor attestation](docs/cleanroom/contributor-attestation.md).

## Licensing

There is no formal contributor licence agreement. By submitting a contribution, you agree that it may be distributed under the repository's [MIT License](LICENSE).

Please also follow the [Code of Conduct](CODE_OF_CONDUCT.md).
