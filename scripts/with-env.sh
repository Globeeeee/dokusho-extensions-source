#!/usr/bin/env bash
# Run a command with the variables from ./.env (gitignored) exported.
# Usage: scripts/with-env.sh ./gradlew :src:en:royalroad:assembleRelease
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi
exec "$@"
