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
# market-data-service intermittently fails with a NoSuchMethodError on a
# protobuf-generated Builder's synthetic access$N() accessor -- a stale/
# out-of-sync compiled inner class, roughly 1-in-4 to 1-in-9 clean builds.
# Root cause looks like an interaction between the module's two separate
# protobuf-maven-plugin executions (org.xolstice:protobuf-maven-plugin,
# archived/unmaintained as of 2025); a same-JVM phase-ordering fix did not
# resolve it, and the vendored .proto files can't be safely changed to test
# further since they're a wire contract with a real external gRPC service.
# Retrying once is a pragmatic mitigation until that gets a real fix.
if ! mvn -f pom.xml clean verify; then
    echo "First attempt failed -- retrying once (see comment above re: known" >&2
    echo "market-data-service protobuf-plugin flake)." >&2
    mvn -f pom.xml clean verify
fi

echo "==> Frontend: install, lint, typecheck+build"
(
    cd frontend
    npm ci
    npm run lint
    npm run build
)

echo "==> Local CI passed"
