#!/usr/bin/env bash
# Reproduce the public sensor references with the original Linux x86-64 release binaries.
# Usage: JAVA=/path/to/jdk25/bin/java bash verify-public-crx.sh /path/to/download-cache
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
mkdir -p -- "${1:?Pass a local scratch/cache directory}"
cd -- "$1"

check() {
    printf '%s  %s\n' "$2" "$1" | sha256sum --check --status
}
fetch() {
    local target="$1" url="$2" expected="$3" staged
    if [[ -f "$target" ]] && check "$target" "$expected"; then
        return
    fi
    staged=$(mktemp "./${target}.XXXXXX")
    if ! curl --fail --location --silent --show-error --retry 3 -o "$staged" "$url" || ! check "$staged" "$expected"; then
        rm -f -- "$staged"
        echo "Unable to obtain a verified $target" >&2
        exit 1
    fi
    mv -f -- "$staged" "$target"
}
fetch dnglab 'https://github.com/dnglab/dnglab/releases/download/v0.8.0/dnglab_linux_x64' d45334659d008b010966f56c3a472677f510a1775d78debd45f5078612ac0683
fetch lightcraft.tar.gz 'https://github.com/storytold/lightcraft/releases/download/v0.21.1/lightcraft-0.21.1-linux-x86_64.tar.gz' b123de16915809a79e64ba4ee3aee9e1cded6ef23b737c06f81cdc7e2f286d62
fetch eos-r.cr3 'https://raw.pixls.us/getfile.php/4543/nice/Canon%20-%20EOS%20R%20-%203:2.CR3' 1b3efe0664a28a0decb53e225cbfecdbc0dd27d85abdc4683fc2a4add5129b4d
fetch g5xm2.cr3 'https://raw.pixls.us/getfile.php/3506/nice/Canon%20-%20PowerShot%20G5%20X%20Mark%20II%20-%20C-RAW%20%283:2%29.CR3' 16d7b944344004c866bf2f84c5673969e76adfe16e5fb4388da48c72bdd58058
tar -xzf lightcraft.tar.gz --strip-components=2 lightcraft-0.21.1-linux-x86_64/bin/lightcraft-cli
check lightcraft-cli ac76e57158d2d361ed9e65b8be859405ea1fa9a4dfd78b926d7ba314dde5d564
chmod u+x dnglab
./dnglab --version >&2
./lightcraft-cli --version >&2
for camera in eos-r g5xm2; do
    ./dnglab analyze --raw-pixel "$camera.cr3" > "$camera.sample"
    ./lightcraft-cli render "$camera.cr3" -o "$camera-lightcraft.dng" --opt dngCompression=uncompressed >&2
done
"${JAVA:-java}" "$here/PublicCrxComparison.java" .
