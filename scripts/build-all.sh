#!/usr/bin/env bash
# Reproducible full build: Vue console first (its output is packaged into the JAR), then Maven.
# Usage: scripts/build-all.sh            (from anywhere)
#        SKIP_MAVEN=1 scripts/build-all.sh   only rebuild the console
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
frontend="$root/oryxos-web/src/main/frontend"

node -v
npm -v

cd "$frontend"
npm ci
npm test
npm run build

if [[ "${SKIP_MAVEN:-}" == "1" ]]; then
  exit 0
fi

cd "$root"
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
