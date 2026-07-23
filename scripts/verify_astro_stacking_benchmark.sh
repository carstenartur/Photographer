#!/usr/bin/env bash
# Builds the optional astro benchmark module and executes a bounded smoke matrix.

set -euo pipefail

readonly OUTPUT_DIR="${ASTRO_JMH_OUTPUT_DIR:-target/astro-jmh-smoke}"
mkdir -p "${OUTPUT_DIR}"

mvn -B --no-transfer-progress \
  -P benchmarks \
  -pl astro-core-benchmark \
  -am \
  package \
  -DskipTests \
  -Djava.awt.headless=true

java -Djava.awt.headless=true \
  -jar astro-core-benchmark/target/benchmarks.jar \
  '.*AstroStackingBenchmark.*' \
  -p size=1024x768 \
  -p mode=MEAN,MEDIAN,SIGMA_CLIPPED_MEAN \
  -wi 0 \
  -i 1 \
  -r 100ms \
  -f 1 \
  -foe true \
  -rf json \
  -rff "${OUTPUT_DIR}/jmh-result.json"
