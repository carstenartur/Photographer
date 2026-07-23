# Operations, Secrets and Recovery

This chapter defines what must be recoverable from Git, what must be backed up privately and how to handle runtime data without turning a snapshot of one workstation into permanent project truth.

## Recovery model

A fresh Git checkout must be sufficient to recover:

- source code and module POMs;
- tests and repository validation scripts;
- committed fixtures and manifests;
- maintained documentation and dated committed evidence;
- Dockerfiles and Compose definitions;
- release-preflight configuration without credentials.

A checkout is **not** expected to contain:

- passwords, API tokens or camera account credentials;
- private signing keys;
- Canon developer manuals or SDK packages that cannot be redistributed;
- production database volumes;
- locally captured fixtures that have not been reviewed and committed;
- generated build output, caches or local search indexes.

## Secrets

Never commit:

- `FOCUSSTACK_SECURITY_ADMIN_PASSWORD`;
- PostgreSQL production credentials;
- Canon CCAPI user names/passwords;
- Gemini or other provider API keys;
- Sonatype Central Portal tokens;
- GPG/PGP private signing keys or passphrases;
- session cookies, Digest headers or authorization logs.

Use local environment files, CI secret stores or a deployment secret manager. `.env` files are ignored. The committed `.env.example` contains names and safe placeholders only.

When collecting diagnostics:

- redact hosts when the endpoint is private;
- remove serial numbers, MAC addresses, GUIDs and camera credentials;
- retain relative CCAPI paths, versions, advertised methods, model and firmware only when required for evidence;
- never log raw `Authorization` or `WWW-Authenticate` values containing reusable state.

## Private Canon reference material

Canon CCAPI/EDSDK manuals and extracted text are intentionally ignored by Git. Keep lawful private copies outside the repository, for example:

- `CameraControlAPI_OperationGuide_EN.pdf`;
- `CameraControlAPI_Reference_v*.pdf`;
- EDSDK manuals/packages obtained through Canon;
- local text extracts used for private navigation.

These files must not be committed or attached to a public release. Repository documentation should record derived interoperability facts in original wording and link them to executable behavior without reproducing the manuals.

Back up private reference material to an encrypted or access-controlled location. A public source registry may record bibliographic identity and permitted use without containing the copyrighted file.

## Runtime data

The application stores operational data outside Git:

- PostgreSQL data in the `postgres-data` Compose volume;
- captured/imported images in the `image-storage` volume or configured filesystem path;
- Hibernate Search/Lucene indexes in the configured search-index volume/path;
- runtime plugin JARs in the configured plugins directory;
- provider-specific transient data and logs.

Search indexes are generally rebuildable from authoritative persisted data. Images and database state may not be.

### Compose backup

Stop writes or place the application in an appropriate maintenance state before taking a consistent backup.

Example PostgreSQL logical backup:

```bash
docker compose exec -T postgres \
  pg_dump -U "${POSTGRES_USER:-focusstack}" \
  "${POSTGRES_DB:-focusstack}" > focusstack.sql
```

Restore into a prepared database:

```bash
cat focusstack.sql | docker compose exec -T postgres \
  psql -U "${POSTGRES_USER:-focusstack}" \
  "${POSTGRES_DB:-focusstack}"
```

Back up image storage separately. Do not assume a database dump contains captured files.

Before using `docker compose down --volumes`, verify that required database and image data have been exported.

## Local development data

The default profile uses H2 in memory, so application state disappears when the process exits. That is expected for local smoke testing.

Generated directories such as `target/`, frontend `dist/`, `node_modules/`, local Lucene indexes and report HTML are rebuildable and must not be treated as backups.

## CR3 fixtures and evidence

A fixture used by a committed regression test must have stable provenance:

- commit the fixture when redistribution and size permit;
- otherwise commit a manifest entry and a deterministic private-reference resolution rule;
- record camera model, capture settings, format/mode and checksum;
- keep irreplaceable private originals in an off-machine backup;
- never let a test silently fall back to a different image.

Generated TIFF/PNG references from approved black-box tools are evidence artifacts. Preserve tool version, command, source fixture checksum and output checksum. The repository reference testkit should verify these bindings.

Ad-hoc screenshots or debug images are disposable unless they are required evidence. When they are required, move them into a reviewed evidence location with provenance rather than linking to `target/`.

## Generated reports

Build reports, Maven Site output, coverage HTML and test HTML are regenerated and published as artifacts or GitHub Pages content. They are not source backups.

Committed scientific reports are different: they preserve dated evidence and must remain reproducible from fixed inputs. Update them only through the owning generator's explicit update mode; CI verifies them without committing changes.

## Release credentials and artifacts

The CR3 release preflight is credential-free. Actual publication additionally requires:

- an approved Central namespace;
- a protected Central Portal token;
- a long-lived protected signing key;
- a published public key as required by the release process;
- an immutable release version and tag.

Back up the signing key securely and separately from the repository. Losing the key does not destroy source code, but it affects continuity of artifact verification. Never use a CI-generated ephemeral preflight key as the project release identity.

Published Maven Central artifacts are immutable. A failed staging deployment is dropped before publication; a defective published version is corrected by a new version, not overwritten.

## Application shutdown

Production shutdown follows bounded ownership:

1. stop admitting new state-changing work;
2. cancel or drain active workflows within configured bounds;
3. release camera session leases;
4. stop live-view/event/media streams;
5. close backend and HTTP resources;
6. stop executors;
7. allow database and framework shutdown.

A new thread, executor, socket or stream reader needs an explicit owner and shutdown test. Relying on daemon termination is not sufficient for production lifecycle management.

## Recovery procedure

For a new development machine:

1. clone the repository;
2. install JDK 25, Maven 3.9+, Node 20.19+ and Docker;
3. restore private Canon references only to ignored local paths when needed;
4. restore required private fixture/reference files according to their manifests;
5. create `.env` from `.env.example` and set a strong local administrator password;
6. run repository validation scripts;
7. run `bash scripts/verify-product.sh`;
8. start the default/H2 or Compose profile;
9. restore database and image data only when operational continuity is required.

## Periodic owner checklist

- repository changes and tags are pushed to the authoritative remote;
- protected branches and workflow permissions are reviewed;
- private Canon references remain outside public Git storage;
- production secrets and signing keys are backed up securely;
- PostgreSQL and image-storage backups are restorable, not merely present;
- new committed fixtures have manifests and checksums;
- uncommitted irreplaceable captures are either committed lawfully or backed up privately;
- generated evidence verifies from fixed inputs;
- obsolete one-shot workflows and write permissions are removed;
- recovery commands are tested after material infrastructure changes.

## What not to preserve as current truth

Do not keep workstation-specific statements such as “these three files are currently untracked” in canonical operations documentation. Such information belongs in an issue or local checklist and expires as soon as the working tree changes.

Likewise, do not list class counts, exact test counts or generated-directory contents as backup requirements unless they are produced by an automated inventory tied to a revision.
