# CR3 Maven releases

A regular Photographer release stages six CR3 libraries with Maven and publishes the resulting Maven directory to the project's `maven-repository` Git branch. Maven Central is an additional, explicit release option. The branch inherits Photographer's repository permissions.

## Default Git-backed repository

Run from the Photographer checkout with JDK 25, Maven 3.9.x, Git and Python 3. The first command performs the Maven file deployment alone. The second performs a fresh deployment, validates every payload, checksums and flattened POM, and resolves `cr3-imageio` and its five sibling libraries using an empty Maven consumer cache.

```bash
mvn -B -ntp -f release/canon-cr3-public/pom.xml -Ppublic-repository-release \
  -Drevision=0.1.5 clean deploy
python3 scripts/cr3_repository.py 0.1.5
```

Both commands produce `target/public-maven-repository/`. Ordinary dependency downloads use Maven's configured repositories. Neither command publishes remotely or needs a Central token or signing key. Select one publisher profile per build.

The normal **Release Workflow** stages the repository during preparation and again at the verified merged commit. A live resume fast-forwards `maven-repository` using the existing checkout permissions, then creates the normal GitHub tag/application release. Each version's POM, binary, sources, Javadoc and checksums are immutable. An identical prior Maven release can be reused. A changed or partial version directory stops publication. GitHub Release remains the archival event for a configured Zenodo integration. Record an archive DOI only after the record exists.

### Resolve a released version

Use your authorized Git access to clone the private distribution branch and select the exact version to verify. Maven reads that checkout through a file URL.

```bash
git clone --single-branch --branch maven-repository \
  https://github.com/carstenartur/Photographer.git ../photographer-maven
mvn -B -f release/canon-cr3-consumer/pom.xml -Pphotographer-releases \
  "-Dphotographer.repository.url=file://$(cd ../photographer-maven && pwd)" \
  "-Dmaven.repo.local=$(mktemp -d)" \
  -Dcr3.version=0.1.5 \
  org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies
```

The command copies the six resolved CR3 runtime JARs and their external dependencies to `release/canon-cr3-consumer/target/dependency/`. For a locally staged candidate, use `"-Dphotographer.repository.url=file://$PWD/target/public-maven-repository"`. The [standalone consumer POM](../release/canon-cr3-consumer/pom.xml) declares one dependency. Copy its `photographer-releases` profile into another consumer's POM or directly add that profile's repository entry under `<project><repositories>`.

A distribution on a public HTTPS server can be consumed anonymously by passing its repository root as `photographer.repository.url`. For a public Photographer GitHub repository, that URL would be `https://raw.githubusercontent.com/carstenartur/Photographer/maven-repository/`. Private GitHub raw URLs do not provide anonymous access. For a version separately published to Maven Central, run the same consumer command without `-Pphotographer-releases` and without the repository URL.

## Public coordinates

The public group is:

```text
io.github.carstenartur
```

The **CR3 ImageIO** library provides Java ImageIO readers and writers for Canon CR3 and uncompressed Bayer DNG. The [ImageIO guide](cr3/imageio.md) describes the supported layouts and write profiles.

The release bundle contains these six artifacts. The examples use version `0.1.5`.

| Artifact | Purpose |
|---|---|
| `cr3-container` | ISO BMFF / CR3 box parsing and payload access |
| `cr3-metadata` | EXIF, MakerNote, CTMD and sensor metadata |
| `cr3-codec` | experimental CRX RAW decoding and linear Bayer model |
| `cr3-processing` | crop, pedestal, demosaic, white-balance and color processing |
| `cr3-core` | decoder facade and camera-profile lossless CR3 writer |
| `cr3-imageio` | `javax.imageio` readers, writers and service-provider integration |

The binary and sources JARs carry the project's MIT notice at `META-INF/cr3/LICENSE`. The ImageIO JAR also contains `META-INF/cr3/imageio.md` and `META-INF/cr3/import.md`. The core JAR contains the import guide. In the respective Javadoc JARs, these files are at the archive root.

The following repository modules are intentionally excluded from the public runtime bundle:

- `canon-cr3-reference-testkit`;
- `canon-cr3-research`;
- `canon-cr3-maven-smoke`;
- `canon-cr3-benchmark`;
- `canon-cr3-cli`;
- the internal monoreactor parent and the release aggregator.

The external Maven smoke test verifies that the runtime dependency graph contains exactly the six public libraries and none of the excluded modules.

## Published POM model

The source modules continue to inherit the repository's internal Spring Boot parent for normal development. Both release profiles use `flatten-maven-plugin` in `oss` mode so the deployed consumer POMs:

- have no parent dependency on the monoreactor;
- contain explicit runtime dependency versions;
- preserve required project, license, developer, SCM and issue metadata;
- contain no test, research or SNAPSHOT dependency.

The dedicated release reactor selects the six libraries. The container test fixtures are installed locally for sibling test suites. Only the runtime JAR, sources JAR, Javadoc JAR and flattened POM for each module go into the Sonatype bundle.

## Optional Central bundle preflight

From the repository root, use JDK 25 and Maven 3.9.x. This command generates a temporary GnuPG key, builds the candidate in an isolated Maven repository and checks the **exact ZIP produced by Sonatype**. It needs Python 3, GnuPG and access to download the Maven build dependencies.

```bash
bash scripts/run_cr3_central_preflight.sh
```

The script derives the candidate version from the root POM's Maven `revision`. Pass a stable version such as `0.1.6` explicitly to preflight that version. The script verifies 24 payloads, their 96 checksums and their 24 detached signatures. It writes `target/cr3-central-preflight/central-bundle.zip`, its SHA-256 digest and the build log, then deletes the temporary keyring and repository. The CR3 Packaged Consumer workflow runs the same preflight for the checked-out candidate version.

With your own signing key, build the bundle directly from Maven. Selecting `central-release` defaults to local bundling. Run Maven in its normal network mode. With `-o`, Maven skips Sonatype’s bundle goal even after all dependencies are cached.

```bash
mvn -B -ntp -f release/canon-cr3-public/pom.xml -Pcentral-release \
  -Drevision=0.1.5 clean deploy
```

Maven logs the bundle path. The default output location in this reactor is `canon-cr3-container/target/central-publishing/central-bundle.zip`. Build inputs use a fixed archive timestamp. Signing times and one-use keys naturally change the detached signatures.

The preflight signs with a disposable key. Use the release key for a Portal deployment. Check the ZIP byte count and the organization's [Central publishing usage](https://central.sonatype.org/publish/maven-central-publishing-limits/) before submitting a release. The bundle includes only the six CR3 libraries and their publication files.

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

To include Central in a verified live release, explicitly select `also_publish_to_central=true` in **Release Workflow**. It defaults to false. The optional **CR3 Maven Central** workflow also accepts a manual run on a stable `vX.Y.Z` tag with `validate` (upload for Portal approval) or `publish`. Both entry points verify the tag and source commit. Check any previous deployment before starting a separate run.

For the first Central publication, you can release the tag and the Git-backed Maven repository through **Release Workflow**, then run **CR3 Maven Central** on that tag with `validate`. In the Portal, check the exact six coordinates and the `VALIDATED` status. Publish that deployment from the Portal to reuse the verified upload.

Central operations use repository secrets `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `MAVEN_GPG_KEY` and, for an encrypted key, `MAVEN_GPG_PASSPHRASE`. The optional `MAVEN_GPG_FINGERPRINT` maps to the GPG plugin’s fingerprint variable.

The release profile and credential names follow the project’s [jgit-storage-hibernate release procedure](https://github.com/carstenartur/jgit-storage-hibernate/blob/540b847706f9d03ac286a21fefd432787e122671/docs/maven-central.md). See also the [Sonatype plugin documentation](https://central.sonatype.org/publish/publish-portal-maven/).

### Verify the published Central version

After the Portal reports `PUBLISHED`, resolve the exact version with a fresh Maven cache and Central enabled in your Maven settings. For example, after `0.1.6` has been published:

```bash
mvn -B -f release/canon-cr3-consumer/pom.xml \
  "-Dmaven.repo.local=$(mktemp -d)" -Dcr3.version=0.1.6 \
  org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies
```

Check that the consumer resolved all six CR3 JARs at the same version. Record the deployment ID and the published version in the release record.

## Consumer examples

Use [the Java ImageIO examples](cr3/imageio.md) for reader registration, preview selection and sensor parameters. For imports with `Cr3Decoder` and original JPEG extraction, see [Import a CR3 file](cr3/import.md).

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

## Verified 0.1.5 distribution

The 0.1.5 archives resolve from the authorized Git-backed repository checkout. An independent consumer started with an empty Maven cache and retrieved all six POMs, runtime JARs, sources and Javadocs. Java 21 discovered the CR3 ImageIO provider from those dependencies. [Pinned Maven tree](https://github.com/carstenartur/Photographer/tree/9af70e857d3f138b2688f377e135e85b850f0c81) · [Checksums and release channels](releases/0.1.5.md#artifact-verification)

For a future Central release, verify the Portal deployment and resolve the same version independently from Maven Central. [Publication and recovery procedure](release-checklist-0.1.5.md#holds-evidence-and-later-distribution)
