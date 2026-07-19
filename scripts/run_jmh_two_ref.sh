#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/run_jmh_two_ref.sh <base-ref> <head-ref> [output-dir]

Builds and runs the CR3 sentinel JMH benchmarks for two Git revisions on the
same machine, then compares their JSON results with scripts/compare_jmh.py.
Temporary detached worktrees keep the caller's checkout and uncommitted files
untouched.

Environment variables:
  JMH_RUN_ORDER       base,head (default) or head,base
  JMH_THRESHOLD       regression ratio passed to the comparator (default 1.15)
  JMH_SAMPLE          CR3 fixture filename (default sample.cr3)
  JMH_WARMUP_ITERS    warm-up iterations (default 2)
  JMH_MEASURE_ITERS   measurement iterations (default 3)
  JMH_WARMUP_TIME     warm-up time per iteration (default 1s)
  JMH_MEASURE_TIME    measurement time per iteration (default 1s)
  JMH_FORKS           forks per revision (default 1)
  MAVEN_OPTS          Maven JVM options (default -Xmx2g when unset)
EOF
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage >&2
  exit 2
fi

for command in git mvn java python3; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command not found: $command" >&2
    exit 2
  fi
done

base_ref=$1
head_ref=$2
output_arg=${3:-target/jmh-two-ref}
run_order=${JMH_RUN_ORDER:-base,head}
threshold=${JMH_THRESHOLD:-1.15}
sample=${JMH_SAMPLE:-sample.cr3}
warmup_iters=${JMH_WARMUP_ITERS:-2}
measure_iters=${JMH_MEASURE_ITERS:-3}
warmup_time=${JMH_WARMUP_TIME:-1s}
measure_time=${JMH_MEASURE_TIME:-1s}
forks=${JMH_FORKS:-1}
export MAVEN_OPTS=${MAVEN_OPTS:--Xmx2g}

case "$run_order" in
  base,head|head,base) ;;
  *)
    echo "JMH_RUN_ORDER must be 'base,head' or 'head,base', got: $run_order" >&2
    exit 2
    ;;
esac

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"
base_sha=$(git rev-parse --verify "${base_ref}^{commit}")
head_sha=$(git rev-parse --verify "${head_ref}^{commit}")

if [[ "$output_arg" = /* ]]; then
  output_dir=$output_arg
else
  output_dir=$repo_root/$output_arg
fi
mkdir -p "$output_dir"
output_dir=$(cd "$output_dir" && pwd -P)

worktree_root=$(mktemp -d "${TMPDIR:-/tmp}/photographer-jmh.XXXXXX")
cleanup() {
  set +e
  git -C "$repo_root" worktree remove --force "$worktree_root/base" >/dev/null 2>&1
  git -C "$repo_root" worktree remove --force "$worktree_root/head" >/dev/null 2>&1
  rm -rf "$worktree_root"
}
trap cleanup EXIT INT TERM

record_environment() {
  {
    echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "base_ref=$base_ref"
    echo "base_sha=$base_sha"
    echo "head_ref=$head_ref"
    echo "head_sha=$head_sha"
    echo "run_order=$run_order"
    echo "threshold=$threshold"
    echo "sample=$sample"
    echo "jmh_warmup_iterations=$warmup_iters"
    echo "jmh_measurement_iterations=$measure_iters"
    echo "jmh_warmup_time=$warmup_time"
    echo "jmh_measurement_time=$measure_time"
    echo "jmh_forks=$forks"
    echo "host=$(hostname 2>/dev/null || echo unknown)"
    echo "kernel=$(uname -a)"
    echo
    echo '## CPU'
    if command -v lscpu >/dev/null 2>&1; then
      lscpu
    else
      echo 'lscpu unavailable'
    fi
    echo
    echo '## Java'
    java -version 2>&1
    echo
    echo '## Maven'
    mvn -version
    echo
    echo '## Git'
    git --version
  } > "$output_dir/environment.txt"
}

benchmark_pattern='com\.photographer\.focusstack\.cr3\.benchmark\.(Cr3BoxParseBenchmark|Cr3MetadataBenchmark|Cr3PreviewExtractBenchmark|CrxLosslessDecodeBenchmark)\..*'

run_revision() {
  local label=$1
  local sha=$2
  local worktree=$worktree_root/$label

  echo "==> Preparing $label worktree at $sha"
  git -C "$repo_root" worktree add --detach "$worktree" "$sha" >/dev/null

  echo "==> Building $label"
  mvn -B --no-transfer-progress -DskipTests \
    -P benchmarks \
    -pl canon-cr3-benchmark -am \
    package \
    -Djava.awt.headless=true \
    -f "$worktree/pom.xml"

  local samples_dir=$worktree/canon-cr3-imageio/src/test/resources/samples
  local fixture=$samples_dir/$sample
  if [[ ! -f "$fixture" ]]; then
    echo "Benchmark fixture not found for $label: $fixture" >&2
    return 1
  fi

  echo "==> Running $label sentinels"
  (
    cd "$worktree"
    java -Djava.awt.headless=true \
      -Dsamples.dir="$samples_dir" \
      -jar canon-cr3-benchmark/target/benchmarks.jar \
      "$benchmark_pattern" \
      -p "sample=$sample" \
      -wi "$warmup_iters" -i "$measure_iters" \
      -w "$warmup_time" -r "$measure_time" -f "$forks" \
      -rf json \
      -rff "$output_dir/$label.json"
  )
}

record_environment
IFS=',' read -r first second <<< "$run_order"
for label in "$first" "$second"; do
  case "$label" in
    base) run_revision base "$base_sha" ;;
    head) run_revision head "$head_sha" ;;
  esac
done

set +e
python3 "$repo_root/scripts/compare_jmh.py" \
  "$output_dir/base.json" \
  "$output_dir/head.json" \
  --threshold "$threshold" \
  --markdown-out "$output_dir/comparison.md" \
  --json-out "$output_dir/comparison.json" \
  --fail-on-regression
comparison_status=$?
set -e

cat "$output_dir/comparison.md"
echo "Results written to $output_dir"
exit "$comparison_status"
