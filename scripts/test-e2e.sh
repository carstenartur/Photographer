#!/usr/bin/env bash
# Exercise a fresh Compose deployment using the public HTTP endpoints.
set -euo pipefail
cd "$(dirname "$0")/.."

# A separate project owns every resource this smoke test tears down.
run_id=$(python3 -c 'import secrets; print(secrets.token_hex(8))')
export COMPOSE_PROJECT_NAME="photographer-smoke-$run_id"
export POSTGRES_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
export FOCUSSTACK_SECURITY_ADMIN_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
export APP_PORT=127.0.0.1:0 POSTGRES_PORT=127.0.0.1:0 CAMERA_MOCK_ENABLED=true SPRING_PROFILES_ACTIVE=docker
compose=(docker compose --env-file /dev/null -f docker-compose.yml)
trap '"${compose[@]}" down --volumes --remove-orphans' EXIT
"${compose[@]}" up --build --detach --wait --wait-timeout 180
binding=$("${compose[@]}" port focusstack 8080 | head -n 1)
base_url="http://127.0.0.1:${binding##*:}"
curl -fsS "$base_url/api/v1/analysis/health" >/dev/null
curl -fsS "$base_url/" | grep -i '<!doctype html' >/dev/null
curl -fsSL "$base_url/swagger-ui.html" >/dev/null
for route in cameras jobs; do
  curl -fsS --user "admin:$FOCUSSTACK_SECURITY_ADMIN_PASSWORD" \
    -H 'Accept: application/json' "$base_url/api/v1/$route" |
    python3 -c 'import json, sys; assert isinstance(json.load(sys.stdin), list)'
done
echo "Compose served the web application and authenticated v1 camera and job lists."
