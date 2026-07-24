# Generated CR3 Evidence Contract

Committed files under `docs/cr3-results/` are scientific evidence snapshots. They are not ordinary prose that may be reformatted or regenerated implicitly during a build.

The machine-readable ownership source is [`generated-evidence.json`](generated-evidence.json). The checkout-runnable implementation is `scripts/manage_cr3_generated_evidence.py`.

## Evidence classifications

### `managed-generated`

The repository has an isolated deterministic generator output and a reviewed mapping to committed files.

A managed entry records:

- the owning module and generator;
- fixed input manifests or source records;
- the exact generation command;
- isolated source-to-committed-output mappings;
- timestamp policy;
- a Git blob lock for every committed managed output.

CI may generate the isolated output and run `compare`, but it never copies files into the checkout.

### `historical-generated`

A generator is known, but the committed snapshot predates the isolated verify/update contract. Common reasons include direct writes into `docs/`, embedded wall-clock timestamps, expired run artifacts or historical report composition that has not yet been separated from execution.

The manifest records the generator, inputs and rationale. The evidence remains byte-for-byte historical until it is migrated deliberately. CI verifies ownership and file presence; it does not claim current regeneration equivalence.

### `historical-manual`

The file is a reviewed synthesis, source audit or durable interpretation assembled from multiple evidence records rather than the direct output of one generator.

It still requires an owner, fixed inputs and a rationale. It must not be presented as machine-generated or silently overwritten by a shorter raw report.

## Commands

Validate schema, paths, generators, ledger references, output coverage and locks:

```bash
python3 scripts/manage_cr3_generated_evidence.py --root . verify
```

Compare isolated output without changing the checkout:

```bash
python3 scripts/manage_cr3_generated_evidence.py \
  --root . \
  compare D12-metrics \
  --source-root canon-cr3-research/target/exact-code-length-k-discriminator
```

Explicitly update one managed entry after review:

```bash
python3 scripts/manage_cr3_generated_evidence.py \
  --root . \
  update D12-metrics \
  --source-root canon-cr3-research/target/exact-code-length-k-discriminator
```

`update` copies only the entry's declared mappings and refreshes its Git blob locks. Review the generated diff and rerun all validators before committing.

## D12 managed example

`ExactBracketKDiscriminatorIT` writes deterministic `results.md`, `results.tsv` and `results.json` under its module `target` directory.

Only the TSV is currently a direct managed committed output:

```text
isolated results.tsv
  -> docs/cr3-results/2026-07-20-D12-exact-k-bracket.tsv
```

The durable D12 Markdown report contains additional evidence binding, interpretation and production decisions. It is therefore registered separately as `historical-manual`; `update D12-metrics` cannot overwrite it.

## CI behavior

The CR3 Clean-room Evidence workflow always runs `verify`. The focused CR3 research workflow generates D12 in `target` and runs `compare` against the committed TSV.

Neither workflow invokes `update`, commits a diff or rewrites scientific evidence. A mismatch fails and requires an explicit reviewed update.

## Adding or migrating evidence

1. Add the generator and fixed inputs before adding outputs.
2. Use exact output paths or narrow family patterns. Blanket `docs/cr3-results/**` ownership is prohibited.
3. Assign every tracked result file to exactly one entry.
4. For historical evidence, state why current isolated regeneration is not claimed.
5. For managed evidence, generate outside `docs/`, add explicit mappings and locks, then prove `compare` passes.
6. Keep timestamps out of deterministic outputs unless the manifest marks them as scientifically required.
7. Update the clean-room experiment ledger and curated results index when the evidence changes durable interpretation.
