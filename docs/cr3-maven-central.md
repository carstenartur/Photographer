# CR3 libraries on Maven Central

This document defines the public Maven surface and the release procedure for Photographer’s clean-room libraries for the Canon CR3 format.

## Public coordinates

The public group is:

```text
io.github.carstenartur
```

The ImageIO library uses the project name **CR3 ImageIO**. It is an independent Java ImageIO reader for CR3 files produced by Canon cameras.

The release bundle contains these six artifacts. The examples below use the candidate version `0.1.5`.

| Artifact | Purpose |
|---|---|
| `cr3-container` | ISO BMFF / CR3 box parsing and payload access |
| `cr3-metadata` | EXIF, MakerNote, CTMD and sensor metadata |
| `cr3-codec` | experimental CRX RAW decoding and linear Bayer model |
| `cr3-processing` | crop, pedestal, demosaic, white-balance and color processing |
| `cr3-core` | decode orchestration and compatibility facade |
| `cr3-imageio` | `javax.imageio` reader and service-provider integration |

The following repository modules are intentionally excluded from the public runtime bundle:

- `canon-cr3-reference-testkit`;
- `canon-cr3-research`;
- `canon-cr3-maven-smoke`;
- `canon-cr3-benchmark`;
- `canon-cr3-cli`;
- the internal monoreactor parent and the release aggregator.

The external Maven smoke test verifies that the runtime dependency graph contains exactly the six public libraries and none of the excluded modules.

## Published POM model

The source modules continue to inherit the repository's internal Spring Boot parent for normal development. The `central-release` profile uses `flatten-maven-plugin` in `oss` mode so the deployed consumer POMs:

- have no parent dependency on the monoreactor;
- contain explicit runtime dependency versions;
- preserve required project, license, developer, SCM and issue metadata;
- contain no test, research or SNAPSHOT dependency.

The dedicated release reactor selects the six libraries. The container test fixtures are installed locally for sibling test suites. Only the runtime JAR, sources JAR, Javadoc JAR and flattened POM for each module go into the Sonatype bundle.

## Build and verify the candidate

From the repository root, use JDK 25 and Maven 3.9.x. This command generates a temporary GnuPG key, builds the candidate in an isolated Maven repository and checks the **exact ZIP produced by Sonatype**. It needs Python 3, GnuPG and access to download the Maven build dependencies.

```bash
bash scripts/run_cr3_central_preflight.sh 0.1.5
```

The script verifies 24 payloads, their 96 checksums and their 24 detached signatures. It writes `target/cr3-central-preflight/central-bundle.zip`, its SHA-256 digest and the build log, then deletes the temporary keyring and repository. The same preflight runs in the CR3 Packaged Consumer workflow.

With your own signing key, build the bundle directly from Maven. Selecting `central-release` defaults to local bundling. Run Maven in its normal network mode. With `-o`, Maven skips Sonatype’s bundle goal even after all dependencies are cached.

```bash
mvn -B -ntp -f release/canon-cr3-public/pom.xml -Pcentral-release \
  -Drevision=0.1.5 clean deploy
```

Maven logs the bundle path. The default output location in this reactor is `canon-cr3-container/target/central-publishing/central-bundle.zip`. Build inputs use a fixed archive timestamp. Signing times and one-use keys naturally change the detached signatures.

## Release credentials

Use a Central Portal user token authorized for `io.github.carstenartur`. Put the token in your secret store and reference its two environment variables in the `servers` section of Maven settings (usually `~/.m2/settings.xml`).

```xml
<server>
  <id>central</id>
  <username>${env.CENTRAL_USERNAME}</username>
  <password>${env.CENTRAL_PASSWORD}</password>
</server>
```

Local signing uses your GnuPG keyring. Select a key with `-Dgpg.keyname=FULL_FINGERPRINT` when needed. For an ASCII-armored private key in `MAVEN_GPG_KEY`, pass `-Dgpg.signer=bc`. The Maven GPG plugin reads `MAVEN_GPG_PASSPHRASE` for encrypted keys and supports the optional `MAVEN_GPG_KEY_FINGERPRINT`. Publish the corresponding public key through a Central-supported keyserver.

## Central Portal publication

Run from the tagged release checkout with a stable version already set in its POM. Maven signs the same set of artifacts and the official extension assembles the bundle as in preflight. Choose one operation.

| Operation | Maven command |
|---|---|
| Upload, wait for validation, then publish this deployment in the Portal | `mvn -B -ntp -f release/canon-cr3-public/pom.xml -Pcentral-release -Dcentral.skipUpload=false clean deploy` |
| Upload, publish automatically and wait for confirmation | `mvn -B -ntp -f release/canon-cr3-public/pom.xml -Pcentral-release -Dcentral.skipUpload=false -Dcentral.autoPublish=true -Dcentral.waitUntil=published clean deploy` |

Record the deployment ID Maven prints. If a run is interrupted after upload, check the existing deployment before retrying. A published version is immutable.

The optional **CR3 Maven Central** Actions workflow runs on manual dispatch. Select the stable `vX.Y.Z` tag and either `validate` (manual Portal publication) or `publish`. The workflow checks out the exact tag commit, confirms its version and invokes the Maven release profile. Use repository secrets named `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `MAVEN_GPG_KEY` and, for an encrypted key, `MAVEN_GPG_PASSPHRASE`. The optional `MAVEN_GPG_FINGERPRINT` secret maps to the Maven GPG plugin’s fingerprint variable.

The release profile and credential names follow the project’s [jgit-storage-hibernate release procedure](https://github.com/carstenartur/jgit-storage-hibernate/blob/540b847706f9d03ac286a21fefd432787e122671/docs/maven-central.md). See also the [Sonatype plugin documentation](https://central.sonatype.org/publish/publish-portal-maven/).

## Consumer examples

ImageIO integration:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>cr3-imageio</artifactId>
  <version>0.1.5</version>
</dependency>
```

Low-level container access:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>cr3-container</artifactId>
  <version>0.1.5</version>
</dependency>
```

The ImageIO dependency resolves the remaining supported runtime modules transitively. Applications do not need to declare research or test infrastructure.

## Current limitation

Repository-side release engineering and credential-free bundle validation do not prove that a version is remotely available. A release may be documented as published only after Central Portal validation, publication and a clean external resolution from Maven Central by exact released coordinates.
