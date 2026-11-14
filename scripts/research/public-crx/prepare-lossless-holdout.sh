#!/usr/bin/env bash
# Preserve the original lossless holdout preparation command.
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
exec bash "$here/prepare-sensor-fixtures.sh" "${1:?Pass a cache directory}" "$here/lossless-holdout.tsv"
