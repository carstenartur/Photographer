#!/usr/bin/env bash
# seed-demo-data.sh — Populate the database with sample sessions and copy
# example frames into the storage volume for demo / development purposes.
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
echo "║  Canon Focus Stack — Seed Demo Data              ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# ── 1. Check prerequisites ──────────────────────────────────────────────────
if ! command -v psql &> /dev/null; then
  echo "✘ psql is not installed. Install PostgreSQL client tools and retry."
  exit 1
fi

echo "▸ Connecting to PostgreSQL at ${DB_HOST}:${DB_PORT}/${DB_NAME}..."

# ── 2. Insert sample sessions ───────────────────────────────────────────────
echo "▸ Inserting sample capture sessions..."

psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -q <<'SQL'
-- Demo session 1: completed macro stack
INSERT INTO capture_session (id, name, status, near_focus, far_focus, step_size, created_at, completed_at)
VALUES (
  'demo-001',
  'Demo — Flower macro',
  'COMPLETED',
  400, 800, 20,
  NOW() - INTERVAL '1 hour',
  NOW() - INTERVAL '58 minutes'
) ON CONFLICT (id) DO NOTHING;

-- Demo session 2: stopped due to movement
INSERT INTO capture_session (id, name, status, near_focus, far_focus, step_size, created_at, completed_at)
VALUES (
  'demo-002',
  'Demo — Insect (movement detected)',
  'STOPPED',
  350, 700, 15,
  NOW() - INTERVAL '30 minutes',
  NOW() - INTERVAL '28 minutes'
) ON CONFLICT (id) DO NOTHING;

-- Demo session 3: in-progress (for UI testing)
INSERT INTO capture_session (id, name, status, near_focus, far_focus, step_size, created_at)
VALUES (
  'demo-003',
  'Demo — Coin stack',
  'CREATED',
  500, 900, 25,
  NOW()
) ON CONFLICT (id) DO NOTHING;
SQL

echo "  ✔ Sessions inserted."

# ── 3. Copy sample frames ───────────────────────────────────────────────────
FIXTURES_DIR="$PROJECT_ROOT/fixtures/sample-stacks"
STORAGE_DIR="$PROJECT_ROOT/storage"

if [ -d "$FIXTURES_DIR" ] && [ "$(ls -A "$FIXTURES_DIR" 2>/dev/null | grep -v .gitkeep)" ]; then
  echo "▸ Copying sample frames to storage..."
  mkdir -p "$STORAGE_DIR/sessions/demo-001"
  cp "$FIXTURES_DIR"/*.jpg "$STORAGE_DIR/sessions/demo-001/" 2>/dev/null || true
  echo "  ✔ Frames copied."
else
  echo "  ⓘ No sample frames found in $FIXTURES_DIR — skipping file copy."
  echo "    Add .jpg files to fixtures/sample-stacks/ to include demo frames."
fi

echo ""
echo "✔ Demo data seeded successfully."
echo "  Open http://localhost:3000 to view the demo sessions."
