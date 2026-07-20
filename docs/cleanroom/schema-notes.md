# Clean-room Evidence Schema Notes

Both JSON files use `schemaVersion: 1`. Changes that remove fields or change their meaning require a schema-version increment and validator support for the migration.

## `source-register.json`

Each source requires:

- `id` — stable `SRC-NNN` identifier;
- `class` — one of `public-specification`, `first-party-observation`, `black-box-reference`, `project-analysis`;
- `title` and `citation`;
- `verifiedDate` in `YYYY-MM-DD` format;
- `permittedUse` and `prohibitedUse`;
- `repositoryEvidence` — non-empty repository-relative paths;
- `integrity` — null for external public publications, otherwise an object describing algorithm and digest/manifest binding.

Source IDs are never reused. A superseded source remains present and may gain a `status` field in a later schema version.

## `experiment-ledger.json`

Each experiment requires:

- stable ID such as `D6`;
- issue number;
- source IDs that exist in the source register;
- exactly one stated changed variable, or an explicit statement that the experiment is observation-only;
- fixed inputs;
- implementation scope;
- durable report for completed decision-bearing experiments;
- verdict and production decision;
- status: `planned`, `running` or `complete`.

A complete modern experiment should also contain:

- 40-character commit SHA;
- positive workflow-run number;
- `sha256:<64 hex>` artifact digest.

Historical experiments may omit these only with `missingRunMetadataReason`.

## Repository path rules

Validator path checks are performed relative to the repository root. Directory entries are permitted where the source represents an entire module. URLs are not accepted as repository evidence paths.

## Updating the ledger

1. Register new sources before using their IDs.
2. Add a planned/running experiment when implementation begins.
3. After CI execution, update commit, workflow run, artifact digest and verdict.
4. Add the deterministic report to `docs/cr3-results/`.
5. Record the production decision even when the result is rejected.
