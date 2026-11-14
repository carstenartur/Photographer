#!/usr/bin/env bash
# Prepare hash-pinned public CR3 files and their DNGLab 0.8.0 native sensor PGMs.
set -euo pipefail
registry=$(realpath -- "${2:?Pass the sensor registry TSV}")
mkdir -p -- "${1:?Pass a cache directory for the Linux x86-64 tool and public files}"
cd -- "$1"

check() {
    printf '%s  %s\n' "$2" "$1" | sha256sum --check --status
}
staged=$(mktemp -d .sensor-fixtures.XXXXXX)
trap 'rm -rf -- "$staged"' EXIT
fetch() {
    local name="$1" hash="$2" url="$3"
    if [[ -f "$name" ]] && check "$name" "$hash"; then
        return
    fi
    curl --fail --location --silent --show-error --retry 3 --max-time 180 -o "$staged/$name" "$url"
    check "$staged/$name" "$hash"
    mv -- "$staged/$name" "$name"
}
fetch dnglab d45334659d008b010966f56c3a472677f510a1775d78debd45f5078612ac0683 \
    https://github.com/dnglab/dnglab/releases/download/v0.8.0/dnglab_linux_x64
chmod u+x dnglab
./dnglab --version
{
    read -r _header
    while IFS=$'\t' read -r id camera group input_sha pgm_sha license url; do
        fetch "$id.cr3" "$input_sha" "$url"
        RAYON_NUM_THREADS=${RAYON_NUM_THREADS:-1} ./dnglab analyze --raw-pixel "$id.cr3" > "$staged/$id.pgm"
        check "$staged/$id.pgm" "$pgm_sha"
        mv -- "$staged/$id.pgm" "$id.pgm"
        printf '%s %s: verified input and reference\n' "$id" "$camera"
    done
} < "$registry"
