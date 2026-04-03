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
echo "  PostgreSQL:   localhost:5432  (focusstack / focusstack)"
echo ""
echo "Next steps:"
echo "  cd backend/camera-service   && mvn spring-boot:run -Dspring-boot.run.profiles=dev"
echo "  cd backend/analysis-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev"
echo "  cd frontend-web             && npm run dev"
