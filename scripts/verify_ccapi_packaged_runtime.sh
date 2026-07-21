#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
IMAGE_TAG=${1:-photographer-ccapi-runtime-smoke:local}
RUN_ID=${GITHUB_RUN_ID:-$$}
NETWORK="photographer-ccapi-smoke-${RUN_ID}"
POSTGRES_CONTAINER="photographer-ccapi-postgres-${RUN_ID}"
APP_CONTAINER="photographer-ccapi-app-${RUN_ID}"
NEGATIVE_CONTAINER="photographer-ccapi-negative-${RUN_ID}"
TEMP_DIR=$(mktemp -d)

cleanup() {
  docker rm -f "$APP_CONTAINER" "$NEGATIVE_CONTAINER" "$POSTGRES_CONTAINER" \
    >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command is unavailable: $1" >&2
    exit 1
  }
}

contains_value() {
  local value=$1
  shift
  grep -aF -- "$value" "$@" >/dev/null 2>&1
}

scan_saved_image() {
  local value=$1
  local image_dir=$2
  local metadata
  while IFS= read -r -d '' metadata; do
    if grep -aFq -- "$value" "$metadata"; then
      return 0
    fi
  done < <(find "$image_dir" -type f -name '*.json' -print0)

  local layer
  while IFS= read -r -d '' layer; do
    # grep exits as soon as it finds a value. Disable pipefail in this bounded
    # subshell so a resulting SIGPIPE from tar cannot hide a positive match.
    if (set +o pipefail; tar -Oxf "$layer" 2>/dev/null \
          | grep -aFq -- "$value"); then
      return 0
    fi
  done < <(find "$image_dir" -type f -name layer.tar -print0)
  return 1
}

wait_for_postgres() {
  for _ in $(seq 1 60); do
    if docker exec "$POSTGRES_CONTAINER" pg_isready -U focusstack >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  docker logs "$POSTGRES_CONTAINER" >&2 || true
  return 1
}

wait_for_app() {
  for _ in $(seq 1 150); do
    if docker exec "$APP_CONTAINER" \
        wget -qO- http://127.0.0.1:8080/api/analysis/health >/dev/null 2>&1; then
      return 0
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$APP_CONTAINER")" != true ]; then
      docker logs "$APP_CONTAINER" >&2 || true
      return 1
    fi
    sleep 1
  done
  docker logs "$APP_CONTAINER" >&2 || true
  return 1
}

require_command docker
require_command grep
require_command tar

cd "$ROOT_DIR"
echo "Building packaged image before runtime sentinels exist..."
docker build --pull -t "$IMAGE_TAG" .

UUID_A=$(cat /proc/sys/kernel/random/uuid)
UUID_B=$(cat /proc/sys/kernel/random/uuid)
CCAPI_USERNAME="ccapi-runtime-user-${UUID_A}"
CCAPI_PASSWORD="ccapi-runtime-password-${UUID_B}"
ADMIN_PASSWORD="Admin-runtime-${UUID_A}-Aa1!"
DB_PASSWORD="Database-runtime-${UUID_B}-Bb2!"
printf '%s' "$CCAPI_USERNAME" > "$TEMP_DIR/username"
printf '%s' "$CCAPI_PASSWORD" > "$TEMP_DIR/password"
chmod 600 "$TEMP_DIR/username" "$TEMP_DIR/password"

mkdir -p "$TEMP_DIR/image"
docker save "$IMAGE_TAG" -o "$TEMP_DIR/image.tar"
tar -xf "$TEMP_DIR/image.tar" -C "$TEMP_DIR/image"
for secret in "$CCAPI_USERNAME" "$CCAPI_PASSWORD"; do
  if scan_saved_image "$secret" "$TEMP_DIR/image"; then
    echo "Runtime CCAPI secret found in saved image layers or configuration" >&2
    exit 1
  fi
  if docker history --no-trunc "$IMAGE_TAG" | grep -aF -- "$secret" >/dev/null; then
    echo "Runtime CCAPI secret found in docker history" >&2
    exit 1
  fi
done

docker network create "$NETWORK" >/dev/null
docker run -d --name "$POSTGRES_CONTAINER" --network "$NETWORK" \
  -e POSTGRES_DB=focusstack \
  -e POSTGRES_USER=focusstack \
  -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  postgres:16-alpine >/dev/null
wait_for_postgres

# Negative control: requiring credentials without mounting a config tree must
# fail before the packaged application becomes ready. The failure is count-only.
docker run -d --name "$NEGATIVE_CONTAINER" --network "$NETWORK" \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://${POSTGRES_CONTAINER}:5432/focusstack" \
  -e SPRING_DATASOURCE_USERNAME=focusstack \
  -e SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
  -e FOCUSSTACK_SECURITY_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  -e CAMERA_MOCK_ENABLED=true \
  -e FOCUSSTACK_CAMERA_CCAPI_RUNTIME_REQUIRED_PROFILE_IDS=camera-smoke \
  "$IMAGE_TAG" >/dev/null
for _ in $(seq 1 90); do
  if [ "$(docker inspect -f '{{.State.Running}}' "$NEGATIVE_CONTAINER")" != true ]; then
    break
  fi
  sleep 1
done
if [ "$(docker inspect -f '{{.State.Running}}' "$NEGATIVE_CONTAINER")" = true ]; then
  echo "Packaged app unexpectedly started without required CCAPI secrets" >&2
  exit 1
fi
NEGATIVE_LOG="$TEMP_DIR/negative.log"
docker logs "$NEGATIVE_CONTAINER" > "$NEGATIVE_LOG" 2>&1 || true
grep -F 'Required CCAPI credentials are missing for 1 configured profile(s)' \
  "$NEGATIVE_LOG" >/dev/null
if grep -aF 'camera-smoke' "$NEGATIVE_LOG" >/dev/null; then
  echo "Required profile id leaked through packaged startup failure" >&2
  exit 1
fi

docker run -d --name "$APP_CONTAINER" --network "$NETWORK" \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://${POSTGRES_CONTAINER}:5432/focusstack" \
  -e SPRING_DATASOURCE_USERNAME=focusstack \
  -e SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
  -e FOCUSSTACK_SECURITY_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  -e CAMERA_MOCK_ENABLED=true \
  -e FOCUSSTACK_CAMERA_CCAPI_RUNTIME_REQUIRED_PROFILE_IDS=camera-smoke \
  --mount "type=bind,src=$TEMP_DIR/username,dst=/run/secrets/focusstack.camera.ccapi.credentials.camera-smoke.username,readonly" \
  --mount "type=bind,src=$TEMP_DIR/password,dst=/run/secrets/focusstack.camera.ccapi.credentials.camera-smoke.password,readonly" \
  "$IMAGE_TAG" >/dev/null
wait_for_app

docker exec "$APP_CONTAINER" test -r \
  /run/secrets/focusstack.camera.ccapi.credentials.camera-smoke.username
docker exec "$APP_CONTAINER" test -r \
  /run/secrets/focusstack.camera.ccapi.credentials.camera-smoke.password

APP_LOG="$TEMP_DIR/app.log"
docker logs "$APP_CONTAINER" > "$APP_LOG" 2>&1
IMAGE_INSPECT="$TEMP_DIR/image-inspect.json"
CONTAINER_INSPECT="$TEMP_DIR/container-inspect.json"
docker image inspect "$IMAGE_TAG" > "$IMAGE_INSPECT"
docker inspect "$APP_CONTAINER" > "$CONTAINER_INSPECT"
for secret in "$CCAPI_USERNAME" "$CCAPI_PASSWORD"; do
  if contains_value "$secret" "$APP_LOG" "$IMAGE_INSPECT" "$CONTAINER_INSPECT"; then
    echo "Runtime CCAPI secret leaked to logs or Docker metadata" >&2
    exit 1
  fi
done
if grep -aEi 'Authorization:[[:space:]]*Digest|nonce=|opaque=' "$APP_LOG" >/dev/null; then
  echo "Digest authentication material leaked to packaged logs" >&2
  exit 1
fi

STOP_START=$(date +%s)
docker stop --time 35 "$APP_CONTAINER" >/dev/null
STOP_SECONDS=$(( $(date +%s) - STOP_START ))
if [ "$STOP_SECONDS" -gt 40 ]; then
  echo "Packaged container exceeded bounded shutdown time: ${STOP_SECONDS}s" >&2
  exit 1
fi
if [ "$(docker inspect -f '{{.State.OOMKilled}}' "$APP_CONTAINER")" != false ]; then
  echo "Packaged container was OOM-killed" >&2
  exit 1
fi

printf 'packaged_runtime=passed\nimage=%s\nshutdown_seconds=%s\n' \
  "$IMAGE_TAG" "$STOP_SECONDS"
