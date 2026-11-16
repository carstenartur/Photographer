# Contributing to Photographer

Contributions are welcome. The canonical checkout-to-pull-request guide is [docs/developer/contributing.md](docs/developer/contributing.md); the [Developer Manual](docs/developer/index.md) defines the architecture, module ownership, testing and documentation boundaries.

## Before opening a pull request

- Keep the change focused and place it in the smallest owning module.
- Run the smallest relevant tests while developing.
- Run `bash scripts/verify-product.sh` for product changes.
- Run frontend tests, lint and build for frontend changes.
- Update canonical and module-local documentation when a public contract changes.
- Keep credentials and private Canon documentation out of contributions.
- Report vulnerabilities through [SECURITY.md](SECURITY.md), not a public issue.

## Camera and CR3 evidence

Hardware claims must state the exact camera model, firmware and connection mode. Tests without real hardware prove protocol and lifecycle behavior, not universal compatibility.

> [!NOTE]
> If you want to keep camera serial numbers, names or location data private, remove those fields from copies of your photos or RAW files before uploading them.

For changes to CR3/CRX behavior, describe the technical references and tests in the pull request. [Clean-room engineering record](docs/cleanroom/README.md)

## Licensing

There is no formal contributor licence agreement. By submitting a contribution, you agree that it may be distributed under the repository's [MIT License](LICENSE).

Please also follow the [Code of Conduct](CODE_OF_CONDUCT.md).
