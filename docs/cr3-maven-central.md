# Canon CR3 libraries: Maven Central release contract

This document defines the public Maven surface and the release procedure for the clean-room Canon CR3 libraries.

## Public coordinates

The public group is:

```text
io.github.carstenartur
```

The first supported release bundle contains exactly these artifacts:

| Artifact | Purpose |
|---|---|
| `canon-cr3-container` | ISO BMFF / CR3 box parsing and payload access |
| `canon-cr3-metadata` | EXIF, MakerNote, CTMD and sensor metadata |
| `canon-cr3-codec` | experimental CRX RAW decoding and linear Bayer model |
| `canon-cr3-processing` | crop, pedestal, demosaic, white-balance and color processing |
| `canon-cr3-core` | decode orchestration and compatibility facade |
| `canon-cr3-imageio` | `javax.imageio` reader and service-provider integration |

The following repository modules are intentionally excluded from the public runtime bundle:

- `canon-cr3-reference-testkit`;
- `canon-cr3-research`;
- `canon-cr3-maven-smoke`;
- `canon-cr3-benchmark`;
- `canon-cr3-cli`;
- the internal monoreactor parent and the release aggregator.

The external Maven smoke test verifies that the runtime dependency graph contains exactly the six public libraries and none of the excluded modules.

## Published POM model

The source modules continue to inherit the repository's internal Spring Boot parent for normal development. The `central-release` profile uses `flatten-maven-plugin` in `ossrh` mode so the deployed consumer POMs:

- have no parent dependency on the monoreactor;
- contain resolved release versions and dependency management;
- preserve required project, license, developer, SCM and issue metadata;
- contain no test, research or SNAPSHOT dependency.

The release aggregator at `release/canon-cr3-public/pom.xml` is not a published parent. It only selects the six supported modules and installs the Central Portal build extension.

## Credential-free preflight

Run from any Git checkout with Java 25, Maven, Python 3 and GnuPG:

```bash
bash scripts/run_cr3_central_preflight.sh 0.1.5
```

The preflight:

1. rejects SNAPSHOT versions;
2. generates a one-day, unprotected ephemeral signing key in a temporary keyring;
3. builds only the six public modules with sources and doclint-checked Javadoc;
4. flattens every consumer POM;
5. signs the POM, main JAR, sources JAR and Javadoc JAR for every artifact;
6. asks the official Central publishing plugin to create its exact deployment bundle with `skipPublishing=true`;
7. verifies all required artifacts, metadata, dependency constraints, MD5/SHA-1/SHA-256/SHA-512 checksums and detached signatures;
8. deletes the ephemeral private key;
9. leaves the validated bundle and SHA-256 digest under `target/cr3-central-preflight/`.

This is also executed in the existing `CR3 Packaged Consumer` workflow. Pull-request CI never has Central credentials and cannot upload.

## Real Central Portal publication

Before the first remote release, the maintainer must complete the external account steps that cannot be encoded in the repository:

1. verify ownership of the `io.github.carstenartur` namespace in the Central Portal;
2. create a Central Portal user token and configure it as Maven server id `central` outside the repository;
3. configure the long-lived release GPG key outside the repository and publish its public key through an accepted keyserver;
4. run the credential-free preflight for the exact release version;
5. inspect the generated bundle and external-consumer result;
6. run the upload with manual publication retained:

```bash
mvn -B --no-transfer-progress \
  -f release/canon-cr3-public/pom.xml \
  -Pcentral-release,central-publish \
  -Drevision=0.1.5 \
  -Dcentral.skipPublishing=false \
  clean deploy
```

`autoPublish` is deliberately `false`. A valid uploaded deployment must be reviewed in the Central Portal before it is published. Released coordinates are immutable; a correction requires a new version.

## Consumer examples

ImageIO integration:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>canon-cr3-imageio</artifactId>
  <version>0.1.5</version>
</dependency>
```

Low-level container access:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>canon-cr3-container</artifactId>
  <version>0.1.5</version>
</dependency>
```

The ImageIO dependency resolves the remaining supported runtime modules transitively. Applications do not need to declare research or test infrastructure.

## Current limitation

Repository-side release engineering and credential-free bundle validation do not prove that a version is remotely available. A release may be documented as published only after Central Portal validation, manual publication and a clean external resolution from Maven Central by exact released coordinates.
