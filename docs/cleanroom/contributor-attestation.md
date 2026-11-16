# CR3 / CRX Contribution Attestation

This optional template can help record references and test evidence for changes to CR3/CRX reader or writer semantics. Earlier changes are documented in the [reviewer guide](reviewer-guide.md#contributor-record).

```text
Clean-room contribution attestation

Affected semantic area and exact change:

Permitted sources consulted:
- source register IDs and revisions:
- additional public sources proposed for registration:

Project evidence used:
- fixtures and reference manifests:
- experiments and reports:
- production decision, where applicable:

AI assistance and material available in the working context, if used:

External tools executed to produce expected outputs:

I derived the contribution from the inputs listed above. I did not
derive it from third-party decoder code or decompiled binaries, and I
excluded snippets and constants of unverified origin or licence.

Prior exposure, including in AI sessions, and its scope:

Limits of what I can confirm:

Name:
Date:
```

## Reviewer checks

- Verify the cited IDs in [`source-register.json`](source-register.json).
- For a semantic experiment, check the changed variable, fixed fixtures, acceptance criteria and deterministic results.
- For a production change, trace the implementation to the cited evidence and its decision in the [experiment ledger](experiment-ledger.json) or the linked promotion record.
- Check that the statement identifies relevant source exposure and any unresolved provenance. Attach privately retained records where appropriate.
