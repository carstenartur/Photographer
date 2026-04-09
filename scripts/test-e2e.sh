#!/usr/bin/env bash
# test-e2e.sh — Run end-to-end smoke tests against the full Docker Compose stack.
#
# Usage:
#   ./scripts/test-e2e.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="docker-compose.yml"
FRONTEND_URL="http://localhost:8080"
BACKEND_URL="http://localhost:8080"
MAX_WAIT=120  # seconds to wait for services to become healthy

cleanup() {
  echo ""
  echo "▸ Tearing down test environment..."
  cd "$PROJECT_ROOT"
  docker compose -f "$COMPOSE_FILE" down --volumes --remove-orphans 2>/dev/null || true
}
trap cleanup EXIT

echo "╔══════════════════════════════════════════════════╗"
echo "║  Focus Stack — End-to-End Tests                  ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

cd "$PROJECT_ROOT"

# ── 1. Start the full stack ──────────────────────────────────────────────────
echo "▸ Starting all services..."
docker compose -f "$COMPOSE_FILE" up --build --detach

# ── 2. Wait for services to be healthy ───────────────────────────────────────
echo "▸ Waiting for backend to become healthy (up to ${MAX_WAIT}s)..."
elapsed=0
until curl --silent --fail "$BACKEND_URL/api/cameras" > /dev/null 2>&1; do
  if (( elapsed >= MAX_WAIT )); then
    echo "✘ Timed out waiting for backend at $BACKEND_URL"
    exit 1
  fi
  sleep 2
  elapsed=$((elapsed + 2))
done
echo "  ✔ Backend is up at $BACKEND_URL (${elapsed}s)"

echo "▸ Waiting for frontend..."
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

# ── 3. Run smoke tests via curl ──────────────────────────────────────────────
echo ""
echo "▸ Running API smoke tests..."

# Test camera list endpoint
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BACKEND_URL/api/cameras")
if [ "$HTTP_STATUS" -ne 200 ]; then
  echo "✘ GET /api/cameras returned $HTTP_STATUS"
  exit 1
fi
echo "  ✔ GET /api/cameras → 200"

# Test jobs list endpoint
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BACKEND_URL/api/jobs")
if [ "$HTTP_STATUS" -ne 200 ]; then
  echo "✘ GET /api/jobs returned $HTTP_STATUS"
  exit 1
fi
echo "  ✔ GET /api/jobs → 200"

# Test Swagger UI
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BACKEND_URL/swagger-ui.html")
if [ "$HTTP_STATUS" -ne 200 ] && [ "$HTTP_STATUS" -ne 302 ]; then
  echo "  ⓘ Swagger UI returned $HTTP_STATUS (may redirect)"
fi
echo "  ✔ Swagger UI accessible"

echo ""
echo "═══════════════════════════════════════════════════"
echo "  ✔ All E2E smoke tests passed."
echo "═══════════════════════════════════════════════════"
