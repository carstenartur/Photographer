#!/usr/bin/env bash
# test-e2e.sh — Run end-to-end tests against the full Docker Compose stack.
#
# Usage:
#   ./scripts/test-e2e.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="docker-compose.yml"
FRONTEND_URL="http://localhost:3000"
MAX_WAIT=120  # seconds to wait for services to become healthy

cleanup() {
  echo ""
  echo "▸ Tearing down test environment..."
  cd "$PROJECT_ROOT"
  docker compose -f "$COMPOSE_FILE" down --volumes --remove-orphans 2>/dev/null || true
}
trap cleanup EXIT

echo "╔══════════════════════════════════════════════════╗"
echo "║  Canon Focus Stack — End-to-End Tests            ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

cd "$PROJECT_ROOT"

# ── 1. Start the full stack ──────────────────────────────────────────────────
echo "▸ Starting all services..."
docker compose -f "$COMPOSE_FILE" up --build --detach

# ── 2. Wait for services to be healthy ───────────────────────────────────────
echo "▸ Waiting for services to become healthy (up to ${MAX_WAIT}s)..."
elapsed=0
until curl --silent --fail "$FRONTEND_URL" > /dev/null 2>&1; do
  if (( elapsed >= MAX_WAIT )); then
    echo "✘ Timed out waiting for frontend at $FRONTEND_URL"
    exit 1
  fi
  sleep 2
  elapsed=$((elapsed + 2))
done
echo "  ✔ Frontend is up at $FRONTEND_URL (${elapsed}s)"

# ── 3. Run Playwright tests ─────────────────────────────────────────────────
echo ""
echo "▸ Running Playwright E2E tests..."
cd "$PROJECT_ROOT/frontend-web"
npx playwright test --reporter=list

echo ""
echo "═══════════════════════════════════════════════════"
echo "  ✔ All E2E tests passed."
echo "═══════════════════════════════════════════════════"
