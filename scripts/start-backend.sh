#!/usr/bin/env bash
# Start KwikQuant backend with .env loaded.
# Usage: ./scripts/start-backend.sh
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PROJECT_ROOT}/.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: .env not found at $ENV_FILE" >&2
  echo "Copy .env.example to .env and fill in the values first." >&2
  exit 1
fi

# Load .env: extract KEY=VALUE pairs, quote values, then source.
while IFS= read -r line; do
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  [[ -z "$line" ]] && continue
  key="${line%%=*}"
  val="${line#*=}"
  export "$key=$val"
done < "$ENV_FILE"

cd "$PROJECT_ROOT"
exec ./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-DsocksProxyHost= -Djava.net.useSystemProxies=false"