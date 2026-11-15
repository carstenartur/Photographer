#!/usr/bin/env bash
# dev-up.sh — Start development infrastructure (PostgreSQL).
#
# Usage:
#   ./scripts/dev-up.sh          Start in foreground
#   ./scripts/dev-up.sh -d       Start detached (background)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DETACH=""
if [[ "${1:-}" == "-d" ]]; then
  DETACH="--detach"
fi

echo "╔══════════════════════════════════════════════════╗"
echo "║  Focus Stack — Development Infrastructure        ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

cd "$PROJECT_ROOT"

echo "▸ Starting PostgreSQL..."
docker compose \
  -f docker-compose.yml \
  up --build $DETACH \
  postgres

echo ""
echo "✔ Infrastructure is ready."
echo ""
echo "  PostgreSQL: see POSTGRES_PORT, POSTGRES_DB and POSTGRES_USER in your Compose configuration."
echo ""
echo "Next steps:"
echo "  See docs/quickstart.md to build the application JAR and set your admin password."
echo "  See docs/developer/contributing.md for the frontend development server."
