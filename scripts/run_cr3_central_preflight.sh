#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/run_cr3_central_preflight.sh [release-version]

Builds the exact six-module Canon CR3 Maven Central bundle without valid Central
credentials or network upload. An ephemeral, unprotected GPG key signs every
artifact; the keyring is deleted after all detached signatures are verified.

The default version is 0.1.5. SNAPSHOT versions are rejected.
EOF
}

if [[ $# -gt 1 ]]; then
  usage >&2
  exit 2
fi

version=${1:-0.1.5}
if [[ -z "$version" || "$version" == *-SNAPSHOT ]]; then
  echo "Release preflight requires a non-SNAPSHOT version, got: $version" >&2
  exit 2
fi

for command in git mvn java python3 gpg; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command not found: $command" >&2
    exit 2
  fi
done

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

output_dir=$repo_root/target/cr3-central-preflight
rm -rf "$output_dir"
mkdir -p "$output_dir"

gnupg_home=$(mktemp -d "${TMPDIR:-/tmp}/photographer-central-gpg.XXXXXX")
chmod 700 "$gnupg_home"
cleanup() {
  rm -rf "$gnupg_home"
}
trap cleanup EXIT INT TERM

cat > "$gnupg_home/key-parameters" <<'EOF'
Key-Type: RSA
Key-Length: 2048
Key-Usage: sign
Name-Real: Photographer CR3 Central Preflight
Name-Email: central-preflight@example.invalid
Expire-Date: 1d
%no-protection
%commit
EOF

# Plugin 0.11.0 resolves the configured server before honoring skipPublishing.
# Supply a temporary, deliberately non-authorizing placeholder and point the
# Central base URL at the local discard port. If skipPublishing ever regresses,
# the build fails locally instead of attempting a real Portal upload.
cat > "$gnupg_home/settings.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <servers>
    <server>
      <id>central</id>
      <username>credential-free-preflight</username>
      <password>not-a-central-token</password>
    </server>
  </servers>
</settings>
EOF

gpg --batch --homedir "$gnupg_home" --generate-key "$gnupg_home/key-parameters"
fingerprint=$(
  gpg --batch --homedir "$gnupg_home" --with-colons --list-secret-keys \
    | awk -F: '$1 == "fpr" { print $10; exit }'
)
if [[ -z "$fingerprint" ]]; then
  echo "Could not determine ephemeral GPG signing-key fingerprint" >&2
  exit 1
fi

python3 scripts/test_validate_cr3_central_bundle.py
python3 scripts/test_validate_cr3_source_coordinates.py
python3 scripts/validate_cr3_source_coordinates.py "$repo_root"

# Keep the Maven transcript inside the always-uploaded diagnostics directory.
# The log contains only public build metadata, a deliberately invalid placeholder
# identity and an ephemeral signing-key id. No valid Portal token is available.
mvn -B --no-transfer-progress \
  --settings "$gnupg_home/settings.xml" \
  -f release/canon-cr3-public/pom.xml \
  -Pcentral-release \
  -Drevision="$version" \
  -DskipTests \
  -Dcentral.skipPublishing=true \
  -DcentralBaseUrl=http://127.0.0.1:9 \
  -Dgpg.homedir="$gnupg_home" \
  -Dgpg.keyname="$fingerprint" \
  -Dgpg.executable=gpg \
  clean deploy 2>&1 | tee "$output_dir/maven-central-preflight.log"

bundle=$(find release/canon-cr3-public -type f \
  -path '*/target/central-publishing/central-bundle.zip' -print -quit)
if [[ -z "$bundle" ]]; then
  bundle=$(find . -type f -path '*/target/central-publishing/central-bundle.zip' \
    -print -quit)
fi
if [[ -z "$bundle" ]]; then
  echo "Central publishing plugin did not create central-bundle.zip" >&2
  exit 1
fi

cp "$bundle" "$output_dir/canon-cr3-$version-central-bundle.zip"
python3 scripts/validate_cr3_central_bundle.py \
  "$output_dir/canon-cr3-$version-central-bundle.zip" \
  "$version" \
  --gnupg-home "$gnupg_home"

(
  cd "$output_dir"
  sha256sum "canon-cr3-$version-central-bundle.zip" \
    > "canon-cr3-$version-central-bundle.zip.sha256"
)

cat > "$output_dir/README.txt" <<EOF
Canon CR3 Maven Central preflight
version=$version
groupId=io.github.carstenartur
artifacts=canon-cr3-container,canon-cr3-metadata,canon-cr3-codec,canon-cr3-processing,canon-cr3-core,canon-cr3-imageio
uploadPerformed=false
portalCredentialsAvailable=false
centralBaseUrl=http://127.0.0.1:9
signingKey=ephemeral-and-deleted
bundle=canon-cr3-$version-central-bundle.zip
EOF

echo "Validated credential-free Central bundle: $output_dir/canon-cr3-$version-central-bundle.zip"
