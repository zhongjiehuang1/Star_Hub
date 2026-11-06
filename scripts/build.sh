#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -B scripts/build-catalog.py
STARHUB_NODE="${STARHUB_NODE:-$(command -v node || true)}"
if [[ -z "$STARHUB_NODE" && -x "$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node" ]]; then
  STARHUB_NODE="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node"
fi
if [[ -z "$STARHUB_NODE" ]]; then
  echo 'Node.js 20+ is required to verify browser storage and localization.' >&2
  exit 1
fi
"$STARHUB_NODE" --test src/test/js/*.test.cjs
if [[ -z "${JAVA_HOME:-}" && -x /Applications/PyCharm.app/Contents/jbr/Contents/Home/bin/java ]]; then
  export JAVA_HOME=/Applications/PyCharm.app/Contents/jbr/Contents/Home
fi
if command -v mvn >/dev/null 2>&1; then
  exec mvn -B clean verify
elif [[ -x .tools/apache-maven-3.9.9/bin/mvn ]]; then
  exec .tools/apache-maven-3.9.9/bin/mvn -B clean verify
else
  echo 'Install Maven 3.9+ and a JDK 11+ before building.' >&2
  exit 1
fi
