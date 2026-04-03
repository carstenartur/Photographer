#!/usr/bin/env bash
# dev-up.sh — Start development infrastructure (PostgreSQL, mock camera).
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
echo "║  Canon Focus Stack — Development Infrastructure  ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

cd "$PROJECT_ROOT"

echo "▸ Starting PostgreSQL and mock camera..."
docker compose \
  -f docker-compose.yml \
  --profile dev-infra \
  up --build $DETACH \
  postgres mock-camera

echo ""
echo "✔ Infrastructure is ready."
echo ""
echo "  PostgreSQL:   localhost:5432  (focusstack / focusstack)"
echo "  Mock Camera:  localhost:9080"
echo ""
echo "Next steps:"
echo "  cd camera-service   && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev"
echo "  cd analysis-service && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev"
echo "  cd frontend-web     && npm run dev"
