# CR3 / CRX Publication Checklist

Apply this checklist to the exact source tree before a visibility change and to the exact artifacts before distributing a CR3 library, application or Fiji update. The [v0.1.5 record](reviewer-guide.md#v015-release-verification) documents the shipped release checks. The [repository publication audit](../public-release.md) covers historical objects and the visibility decision.

## Provenance

- [ ] [`source-register.json`](source-register.json) validates. Review the decision-bearing sources for the changes in scope, including any recovered early records.
- [ ] [`experiment-ledger.json`](experiment-ledger.json) validates and exposes each verdict and production decision.
- [ ] The [development history](development-history.md) links the release to the relevant issues, PRs and commits.
- [ ] Every completed experiment has a durable report.
- [ ] Completed experiments identify the durable report and its digest. The ledger records the commit and workflow run or explains missing run metadata.
- [ ] Rejected and partial experiments remain discoverable.
- [ ] Production semantics are linked to supported experiments or explicitly marked experimental.

## Clean-room review

- [ ] The maintainer has reviewed the [attestation record](reviewer-guide.md#contributor-record) for reader and writer changes, including prior exposure and inputs supplied to AI tools.
- [ ] No third-party CR3/CRX decoder source has been copied or translated.
- [ ] No decompiled proprietary implementation is present.
- [ ] Public patents, standards and vendor documents are cited accurately.
- [ ] Reference-only tools are described as black-box oracles, not specifications.
- [ ] Any contamination incident has been remediated and documented without reproducing protected material.

## Fixtures and privacy

- [ ] Each fixture intended for public access has been reviewed for visible private content and identifying metadata.
- [ ] For retained first-party originals, the publication decision covers both the image and the embedded metadata. [Historical fixture policy](../public-release.md#sensitive-binary-history)
- [ ] Private fixtures are represented by hashes and redacted metadata rather than committed content.
- [ ] G5 X Mark II and EOS R capture provenance is documented.

## Integrity and reproducibility

- [ ] Reference manifest validation succeeds from a fresh checkout.
- [ ] Clean-room evidence validation succeeds from a fresh checkout.
- [ ] Fixed experiment panels do not expand implicitly when fixtures are added.
- [ ] Markdown, JSON and TSV outputs are deterministic.
- [ ] Current evidence can be regenerated using documented Maven and script commands.
- [ ] Decision-bearing GitHub Actions artifacts have durable copies or release assets before expiry.

## Licensing and distribution

- [ ] Repository and module licences are present and internally consistent.
- [ ] Fixture and reference-output redistribution rights have been reviewed.
- [ ] Public artifact POMs include licence, source and developer metadata.
- [ ] Experimental codec APIs are labelled as such.
- [ ] Record the current status and claim review for applicable patent families and intended distribution markets. [Patent review record](reviewer-guide.md#patent-review-record)

## Security

- [ ] Secret scanning finds no credentials, Authorization headers, nonces or Wi-Fi data.
- [ ] Camera serials, MAC addresses, UDNs and private host addresses are absent or redacted.
- [ ] Git history has been reviewed for previously committed secrets or private media.
- [ ] Docker layers and generated configuration contain no embedded runtime secrets.

## Public documentation

- [ ] The [CR3 manual](../cr3/current-state.md) states the measured native RAW and C-RAW reader coverage and the experimental lossless writer's scope.
- [ ] Supported cameras and transports are tied to evidence.
- [ ] Known decoder limitations are prominent.
- [ ] Clean-room process and its limitations are linked from developer documentation.
- [ ] Reproduction commands and expected outputs are current.
