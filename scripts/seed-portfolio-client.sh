#!/bin/bash
# On-demand portfolio provisioning for exchange-core's
# EXCHANGE_CORE_ACCOUNTING_MODE=full-equity-risk mode: seeds a USD (asset
# 840) balance for <username>'s deterministic exchange-core client id, so
# orders placed as that user can load a risk seed. run-local.sh and
# run-infra-docker.sh already do this for the bootstrap admin at startup --
# use this for any additional trading user created afterwards (e.g. via the
# admin user-management UI).
#
# Usage: scripts/seed-portfolio-client.sh <username> [balance]
#
# Connects to the Mode 1 (Local) single database by default
# (localhost:5432/emporia, role defaulting to your OS username -- the role
# Homebrew's postgresql formula creates by default). For Mode 2
# (Infrastructure-only Docker, role "postgres"), override:
#   DB_HOST=localhost DB_PORT=5438 DB_NAME=emporia_portfolio \
#     scripts/seed-portfolio-client.sh <username>
set -e

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=lib/run-common.sh
source "$repo_root/scripts/lib/run-common.sh"

username="${1:?Usage: $0 <username> [balance]}"
[ -n "${2:-}" ] && PORTFOLIO_SEED_BALANCE="$2"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-emporia}"
if [ "$DB_PORT" = "5432" ]; then
    DB_USERNAME="${DB_USERNAME:-$(whoami)}"
else
    DB_USERNAME="${DB_USERNAME:-postgres}"
fi

PGPASSWORD="${DB_PASSWORD:-admin123}" \
provision_portfolio_client "$username" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$DB_NAME"
