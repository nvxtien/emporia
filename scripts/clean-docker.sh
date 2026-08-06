#!/usr/bin/env bash
# ==============================================================================
# Emporia Docker Container & Storage Cleanup Utility
# Purges containers, data volumes, networks, and orphan storage
# ==============================================================================

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

DRY_RUN=false
PURGE_ALL=false

for arg in "$@"; do
    case "$arg" in
        --dry-run)
            DRY_RUN=true
            ;;
        --all|--force)
            PURGE_ALL=true
            ;;
        *)
            ;;
    esac
done

echo "==> Emporia Docker & Storage Cleanup Utility"
echo "    Repo Root: ${repo_root}"
echo "    Purge All Volumes/System: ${PURGE_ALL}"
echo

if [[ "$DRY_RUN" == "true" ]]; then
    echo "[Clean Docker] Dry-run mode verified. No containers or storage purged."
    exit 0
fi

echo "1. Stopping running host services & Docker containers..."
if [[ -f "./scripts/stop-services.sh" ]]; then
    ./scripts/stop-services.sh || true
fi

echo "2. Removing Docker compose containers and data volumes..."
docker compose down -v --remove-orphans 2>/dev/null || true
docker compose -f docker-compose.full.yml down -v --remove-orphans 2>/dev/null || true

echo "3. Cleaning Emporia data volumes..."
docker volume ls -q -f name=emporia | xargs -r docker volume rm 2>/dev/null || true

echo "4. Pruning unused Docker networks..."
docker network prune -f 2>/dev/null || true

if [[ "$PURGE_ALL" == "true" ]]; then
    echo "5. Performing full system prune (containers, images, volumes)..."
    docker system prune -af --volumes 2>/dev/null || true
else
    echo "5. Pruning dangling volumes..."
    docker volume prune -f 2>/dev/null || true
fi

echo
echo "==> Docker container and storage cleanup COMPLETE!"
echo "=============================================================================="
