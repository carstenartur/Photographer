# CR3 / CRX Publication Checklist

Complete this checklist before changing the repository from private to public or publishing the decoder artifacts.

## Provenance

- [ ] `source-register.json` validates and lists every decision-bearing source.
- [ ] `experiment-ledger.json` validates and includes every current decoder hypothesis.
- [ ] Every completed experiment has a durable report.
- [ ] Every completed post-ledger experiment records commit SHA, workflow run and artifact digest.
- [ ] Rejected and partial experiments remain discoverable.
- [ ] Production semantics are linked to supported experiments or explicitly marked experimental.

## Clean-room review

- [ ] Decoder contributors have reviewed the contributor attestation.
- [ ] No third-party CR3/CRX decoder source has been copied or translated.
- [ ] No decompiled proprietary implementation is present.
- [ ] Public patents, standards and vendor documents are cited accurately.
- [ ] Reference-only tools are described as black-box oracles, not specifications.
- [ ] Any contamination incident has been remediated and documented without reproducing protected material.

## Fixtures and privacy

- [ ] Every public fixture is approved for publication.
- [ ] Images contain no faces, addresses, documents, location data or other unintended private content.
- [ ] EXIF/GPS and stable camera identifiers have been reviewed or removed where unnecessary.
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
- [ ] Patent references are factual and do not imply a legal clearance opinion.

## Security

- [ ] Secret scanning finds no credentials, Authorization headers, nonces or Wi-Fi data.
- [ ] Camera serials, MAC addresses, UDNs and private host addresses are absent or redacted.
- [ ] Git history has been reviewed for previously committed secrets or private media.
- [ ] Docker layers and generated configuration contain no embedded runtime secrets.

## Public documentation

- [ ] README distinguishes container/metadata support from experimental RAW decoding.
- [ ] Supported cameras and transports are tied to evidence.
- [ ] Known decoder limitations are prominent.
- [ ] Clean-room process and its limitations are linked from developer documentation.
- [ ] Reproduction commands and expected outputs are current.
