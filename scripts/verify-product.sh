#!/usr/bin/env bash
# Canonical product verification used by contributors and CI.
#
# This intentionally excludes research-only, Maven-smoke and benchmark modules.
# The selected runtime modules still pull all required upstream modules through -am.

set -euo pipefail

readonly PRODUCT_MODULES="common-api,plugin-api,capture-core,storage,camera-ptp,hdr-core,video-core,face-analysis-core,face-profile-core,session-core,observer-core,ai-vision,astro-core,canon-cr3-container,canon-cr3-metadata,canon-cr3-codec,canon-cr3-processing,canon-cr3-core,canon-cr3-imageio,canon-cr3-cli,app"

exec mvn -B --no-transfer-progress clean verify \
  -pl "${PRODUCT_MODULES}" \
  -am \
  -Djava.awt.headless=true \
  "$@"
