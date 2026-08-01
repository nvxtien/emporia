#!/bin/bash
# Local CI pipeline: backend build+test+PMD, then frontend lint+typecheck+build.
# Run directly (`scripts/local-ci.sh`) or via the pre-push hook installed by
# scripts/install-git-hooks.sh. Exits non-zero on the first failure.
set -e

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

echo "==> Checking exchange-core is installed locally"
if ! mvn -q -f pom.xml dependency:get \
        -Dartifact=exchange.core2:exchange-core:0.5.4-SNAPSHOT \
        -o >/dev/null 2>&1; then
    echo "exchange-core is not in your local Maven repository." >&2
    echo "Clone and install it first (see README.md):" >&2
    echo "  git clone https://github.com/nvxtien/exchange-core.git && cd exchange-core && mvn clean install" >&2
    exit 1
fi

echo "==> Backend: compile, test, PMD (mvn clean verify)"
mvn -f pom.xml clean verify

echo "==> Frontend: install, lint, typecheck+build"
(
    cd frontend
    npm ci
    npm run lint
    npm run build
)

echo "==> Local CI passed"
