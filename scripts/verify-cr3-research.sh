#!/usr/bin/env bash
# Build research tests and their test-JAR prerequisites in one Maven reactor.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
caller_dir=$PWD
cd "$repo_root"

mode="${1:---focused}"
if [[ $# -gt 0 ]]; then
  shift
fi
selection=()
case "$mode" in
  --focused)
    selection=(-Dit.test=ExactBracketKDiscriminatorIT,PatentRunModeIT,LosslessOpeningSamplesIT
               -Dcr3.patent.run-mode.enabled=true -Dcr3.opening.samples.enabled=true)
    ;;
  --opening)
    if [[ $# -eq 0 || -z $1 || $1 == -* ]]; then
      echo 'Usage: verify-cr3-research.sh --opening CACHE_DIRECTORY [Maven options...]' >&2
      exit 2
    fi
    cache=$1
    shift
    if [[ $cache != /* ]]; then
      cache=$caller_dir/$cache
    fi
    bash scripts/research/public-crx/prepare-lossless-holdout.sh "$cache"
    cache=$(cd -- "$cache" && pwd)
    selection=(-Dit.test=LosslessOpeningSamplesIT,LosslessOpeningHoldoutIT,FirstSampleCausalIT
               -Dcr3.opening.samples.enabled=true -Dcr3.opening.holdout.enabled=true
               "-Dcr3.opening.holdout.dir=$cache" -Dcr3.first-sample.control.enabled=true
               "-Dcr3.first-bit.dnglab=$cache/dnglab")
    ;;
  --full) ;;
  *) echo 'Usage: verify-cr3-research.sh [--focused|--full|--opening CACHE_DIRECTORY] [Maven options...]' >&2; exit 2 ;;
esac

# Do not stage dependencies with maven.test.skip: that also suppresses the
# attached test JARs consumed by downstream modules. clean verify -am builds
# them before their consumers without depending on cached project artifacts.
mvn -B --no-transfer-progress clean verify \
  -Pcr3-research-it \
  -pl canon-cr3-research -am \
  -Djava.awt.headless=true \
  "${selection[@]}" "$@"

compare() {
  python3 scripts/manage_cr3_generated_evidence.py \
    --root . compare "$1" --source-root "canon-cr3-research/target/$2"
}
if [[ "$mode" == --focused ]]; then
  compare D12-metrics exact-code-length-k-discriminator
  compare RG2-metrics patent-run-mode
fi
if [[ "$mode" == --focused || "$mode" == --opening ]]; then
  compare RG3-discovery lossless-opening-samples
fi
if [[ "$mode" == --opening ]]; then
  compare RG3-holdout opening-holdout
  compare RG3-interventions first-sample-causality
fi
