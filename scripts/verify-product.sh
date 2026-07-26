#!/usr/bin/env bash
# Canonical product verification used by contributors and CI.
#
# This intentionally excludes research-only, Maven-smoke and benchmark modules.
# The selected runtime modules still pull all required upstream modules through -am.

set -euo pipefail

readonly PRODUCT_MODULES="common-api,plugin-api,capture-core,storage,camera-ptp,hdr-core,video-core,face-analysis-core,face-profile-core,session-core,observer-core,ai-vision,astro-core,canon-cr3-container,canon-cr3-metadata,canon-cr3-codec,canon-cr3-processing,canon-cr3-core,canon-cr3-imageio,canon-cr3-cli,app"

python3 scripts/test_validate_vision_module_boundaries.py
python3 scripts/validate_vision_module_boundaries.py .
python3 scripts/test_validate_documentation.py
python3 scripts/validate_documentation.py .
python3 scripts/test_audit_public_release.py
python3 scripts/test_sanitize_cr3_fixtures.py
python3 scripts/test_audit_historical_media.py
python3 scripts/audit_public_release.py

exec mvn -B --no-transfer-progress clean verify \
  -pl "${PRODUCT_MODULES}" \
  -am \
  -Djava.awt.headless=true \
  "$@"
