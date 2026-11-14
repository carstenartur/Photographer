#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/run_cr3_central_preflight.sh [release-version]

Builds the exact six-module CR3 Maven Central bundle without valid Central
credentials or network upload. An ephemeral, unprotected GPG key signs every
artifact; the keyring and isolated Maven repository are deleted after all
bundle files, checksums and detached signatures are verified.

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
maven_repository=$gnupg_home/maven-repository
mkdir -p "$maven_repository"
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

# Point the official plugin at a fresh output directory, and validate its unmodified ZIP.
mvn -B --no-transfer-progress \
  -Dmaven.repo.local="$maven_repository" \
  -f release/canon-cr3-public/pom.xml \
  -Pcentral-release \
  -Drevision="$version" \
  -DskipTests \
  -Dcentral.skipUpload=true \
  -DcentralBaseUrl=http://127.0.0.1:9 \
  -DoutputDirectory="$output_dir/bundle" \
  -Dgpg.homedir="$gnupg_home" \
  -Dgpg.keyname="$fingerprint" \
  clean deploy 2>&1 | tee "$output_dir/maven-central-preflight.log"

bundle=$output_dir/central-bundle.zip
mv "$output_dir/bundle/central-bundle.zip" "$bundle"
rmdir "$output_dir/bundle"
python3 scripts/validate_cr3_central_bundle.py \
  "$bundle" \
  "$version" \
  --gnupg-home "$gnupg_home"

(
  cd "$output_dir"
  sha256sum "central-bundle.zip" \
    > "central-bundle.zip.sha256"
)

cat > "$output_dir/README.txt" <<EOF
CR3 Maven Central preflight
version=$version
groupId=io.github.carstenartur
artifacts=cr3-container,cr3-metadata,cr3-codec,cr3-processing,cr3-core,cr3-imageio
uploadPerformed=false
centralExtensionLoaded=true
centralSkipUpload=true
centralBaseUrl=http://127.0.0.1:9
sourceRepository=isolated-and-deleted
includedClassifiers=main,sources,javadoc
excludedClassifiers=tests
signingKey=ephemeral-and-deleted
bundle=central-bundle.zip
EOF

echo "Validated credential-free Central bundle: $bundle"
