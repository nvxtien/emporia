#!/bin/bash
# Stops everything any run/deploy script might have started, regardless of
# which mode you used: host-JVM/npm processes from scripts/run-local.sh or
# scripts/run-infra-docker.sh, the Kafka and/or per-service PostgreSQL
# containers from docker-compose.yml (Mode 1/2), and the full-stack
# containers from docker-compose.full.yml (Full Docker /
# scripts/local-deploy.sh). `docker compose down` is a no-op for containers
# that were never started, so it's safe to run all of this unconditionally.
# Leaves data volumes in place; add -v to the docker compose commands by
# hand if you intentionally want to delete them.
set -e

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
pid_dir="$repo_root/.local-run/pids"

# shellcheck source=lib/run-common.sh
source "$repo_root/scripts/lib/run-common.sh"

if [ -d "$pid_dir" ] && [ -n "$(ls -A "$pid_dir" 2>/dev/null)" ]; then
    for pid_file in "$pid_dir"/*.pid; do
        name="$(basename "$pid_file" .pid)"
        pid="$(cat "$pid_file")"
        echo "==> Stopping $name (pid=$pid)"
        stop_pid_tree "$pid"
        rm -f "$pid_file"
    done
else
    echo "No host-JVM/npm services to stop (no $pid_dir pid files)."
fi

echo "==> Stopping Kafka and per-service PostgreSQL containers (Mode 1/2)"
docker compose down

echo "==> Stopping full-stack Docker containers (Full Docker)"
docker compose -f docker-compose.full.yml down

echo "==> All local services and containers stopped"
