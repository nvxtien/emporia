#!/bin/bash
# Local CD: builds fresh Docker images and (re)starts the full local stack.
# Run on demand (e.g. after scripts/local-ci.sh passes) -- not triggered
# automatically by a push, since restarting local containers as a side effect
# of `git push` would be surprising and could interrupt other work in progress.
set -e

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

echo "==> Building images and (re)starting the full local stack"
docker compose -f docker-compose.full.yml up -d --build

echo "==> Local deployment complete"
docker compose -f docker-compose.full.yml ps
