#!/usr/bin/env bash
# seed-demo-data.sh — Populate the database with sample profiles and jobs
# for demo / development purposes.
#
# Usage:
#   ./scripts/seed-demo-data.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-focusstack}"
DB_USER="${DB_USER:-focusstack}"
DB_PASSWORD="${DB_PASSWORD:-focusstack}"

export PGPASSWORD="$DB_PASSWORD"

echo "╔══════════════════════════════════════════════════╗"
echo "║  Focus Stack — Seed Demo Data                    ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# ── 1. Check prerequisites ──────────────────────────────────────────────────
if ! command -v psql &> /dev/null; then
  echo "✘ psql is not installed. Install PostgreSQL client tools and retry."
  exit 1
fi

echo "▸ Connecting to PostgreSQL at ${DB_HOST}:${DB_PORT}/${DB_NAME}..."

# ── 2. Insert sample camera profiles ────────────────────────────────────────
echo "▸ Inserting sample camera profiles..."

psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -q <<'SQL'
INSERT INTO camera_profiles (id, name, backend_type, host, default_step_size, max_frames, movement_threshold)
VALUES (
  'demo-cam-001',
  'Demo — Mock Camera',
  'mock',
  'localhost',
  10, 50, 0.02
) ON CONFLICT (id) DO NOTHING;

INSERT INTO camera_profiles (id, name, backend_type, host, default_step_size, max_frames, movement_threshold)
VALUES (
  'demo-cam-002',
  'Demo — Canon G5X',
  'canon-ccapi',
  '192.168.1.42',
  5, 30, 0.015
) ON CONFLICT (id) DO NOTHING;
SQL

echo "  ✔ Camera profiles inserted."

# ── 3. Insert sample stacking jobs ──────────────────────────────────────────
echo "▸ Inserting sample stacking jobs..."

psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -q <<'SQL'
INSERT INTO stacking_jobs (id, camera_profile_id, status, started_at, completed_at,
  param_start_position, param_step_size, param_max_frames,
  param_abort_threshold, param_motion_detection_enabled, param_quality_mode,
  captured_frame_ids)
VALUES (
  'demo-job-001',
  'demo-cam-001',
  'COMPLETED',
  NOW() - INTERVAL '1 hour',
  NOW() - INTERVAL '58 minutes',
  0, 10, 20, 0.05, true, 'BALANCED',
  'frame-001,frame-002,frame-003,frame-004,frame-005'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO stacking_jobs (id, camera_profile_id, status,
  param_start_position, param_step_size, param_max_frames,
  param_abort_threshold, param_motion_detection_enabled, param_quality_mode,
  captured_frame_ids)
VALUES (
  'demo-job-002',
  'demo-cam-001',
  'CREATED',
  0, 5, 30, 0.03, true, 'MAXIMUM_QUALITY',
  ''
) ON CONFLICT (id) DO NOTHING;
SQL

echo "  ✔ Stacking jobs inserted."

# ── 4. Copy sample frames ───────────────────────────────────────────────────
FIXTURES_DIR="$PROJECT_ROOT/fixtures/sample-stacks"

if [ -d "$FIXTURES_DIR" ] && [ "$(ls -A "$FIXTURES_DIR" 2>/dev/null | grep -v .gitkeep)" ]; then
  echo "▸ Sample frames found in $FIXTURES_DIR"
else
  echo "  ⓘ No sample frames found in $FIXTURES_DIR — skipping file copy."
  echo "    Add .jpg files to fixtures/sample-stacks/ to include demo frames."
fi

echo ""
echo "✔ Demo data seeded successfully."
echo "  Open http://localhost:8080 to view the demo data."
