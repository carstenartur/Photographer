#!/usr/bin/env bash
# Build research tests and their test-JAR prerequisites in one Maven reactor.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

mode="${1:---focused}"
if [[ $# -gt 0 ]]; then
  shift
fi
selection=()
case "$mode" in
  --focused) selection=(-Dit.test=ExactBracketKDiscriminatorIT,PatentRunModeIT -Dcr3.patent.run-mode.enabled=true) ;;
  --full) ;;
  *) echo 'Usage: verify-cr3-research.sh [--focused|--full] [Maven options...]' >&2; exit 2 ;;
esac

# Do not stage dependencies with maven.test.skip: that also suppresses the
# attached test JARs consumed by downstream modules. clean verify -am builds
# them before their consumers without depending on cached project artifacts.
mvn -B --no-transfer-progress clean verify \
  -Pcr3-research-it \
  -pl canon-cr3-research -am \
  -Djava.awt.headless=true \
  "${selection[@]}" "$@"

if [[ "$mode" == --focused ]]; then
  python3 scripts/manage_cr3_generated_evidence.py \
    --root . compare D12-metrics \
    --source-root canon-cr3-research/target/exact-code-length-k-discriminator
  python3 scripts/manage_cr3_generated_evidence.py \
    --root . compare RG2-metrics \
    --source-root canon-cr3-research/target/patent-run-mode
fi
