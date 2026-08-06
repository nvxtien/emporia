#!/bin/bash
# Run mode 1 (Local), automated: every Spring service in the host JVM
# against a single local non-Docker PostgreSQL instance, plus a Dockerized
# Kafka broker. Mirrors the "Start locally" walkthrough in README.md but
# launches each service in the background so one command brings up the
# whole stack.
#
# Prerequisite: the non-Docker local PostgreSQL described in README.md
# (database `emporia`, password `admin123`) must already be running on
# localhost:5432. DB_USERNAME defaults to your OS username, matching the
# role Homebrew's postgresql formula creates by default (not "postgres");
# export DB_USERNAME to override if your local Postgres uses a different role.
#
# execution-service defaults to EXECUTION_VENUE_MODE=exchange-core; export a
# different value before running this script to override. market-data-service
# defaults to MARKET_DATA_PROVIDER=simulated; export MARKET_DATA_PROVIDER=alpaca-iex
# plus APCA_API_KEY_ID/APCA_API_SECRET_KEY to use live Alpaca IEX data instead.
#
# Stop everything this script started with scripts/stop-services.sh.
set -e

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
log_dir="$repo_root/.local-run/logs"
pid_dir="$repo_root/.local-run/pids"
rm -rf "$pid_dir"
mkdir -p "$log_dir" "$pid_dir"

# shellcheck source=lib/run-common.sh
source "$repo_root/scripts/lib/run-common.sh"

check_exchange_core

BOOTSTRAP_ADMIN_USERNAME="${BOOTSTRAP_ADMIN_USERNAME:-admin}"
DB_USERNAME="${DB_USERNAME:-$(whoami)}"

echo "==> Starting Kafka (Docker)"
docker compose up -d kafka
wait_docker_healthy kafka docker-compose.yml

# Observability stack (REWORK_NOTE Phase 1_1). Not health-waited: services
# export OTLP best-effort and start fine if the collector lags.
echo "==> Starting observability stack (collector, Tempo, Prometheus, Grafana)"
docker compose up -d otel-collector tempo prometheus grafana

# Ensure target directories are writeable and clear macOS xattr quarantine flags
xattr -rc */target 2>/dev/null || true
chmod -R +w */target 2>/dev/null || true

echo "==> Pre-building contract modules (fix-simulator-contracts, fix-market-simulator)"
mvn -q -pl fix-simulator-contracts,fix-market-simulator install -DskipTests

echo "==> Building and installing reactor modules (mvn install -DskipTests)"
mvn -q -f pom.xml install -DskipTests

AUTH_ISSUER=http://localhost:3001 \
OAUTH_REDIRECT_URI=http://localhost:3001/auth/callback \
OAUTH_POST_LOGOUT_REDIRECT_URI=http://localhost:3001/auth/logout-callback \
BOOTSTRAP_ADMIN_ENABLED=true \
BOOTSTRAP_ADMIN_USERNAME="$BOOTSTRAP_ADMIN_USERNAME" \
BOOTSTRAP_ADMIN_EMAIL=admin@localhost \
BOOTSTRAP_ADMIN_PASSWORD=admin123 \
BOOTSTRAP_ADMIN_DESK=default \
BOOTSTRAP_ADMIN_CAN_TRADE=true \
DB_USERNAME="$DB_USERNAME" \
DB_PASSWORD=admin123 \
start_service authentication authentication mvn spring-boot:run

wait_http_health authentication http://localhost:9000/actuator/health

DB_USERNAME="$DB_USERNAME" DB_PASSWORD=admin123 start_service static-data-service static-data-service mvn spring-boot:run
DB_USERNAME="$DB_USERNAME" DB_PASSWORD=admin123 start_service user-preferences-service user-preferences-service mvn spring-boot:run
start_service market-data-service market-data-service mvn spring-boot:run
DB_USERNAME="$DB_USERNAME" DB_PASSWORD=admin123 start_service order-management-service order-management-service mvn spring-boot:run
DB_USERNAME="$DB_USERNAME" DB_PASSWORD=admin123 start_service portfolio-service portfolio-service mvn spring-boot:run

wait_http_health portfolio-service http://localhost:8088/actuator/health
PGPASSWORD=admin123 provision_portfolio_client "$BOOTSTRAP_ADMIN_USERNAME" psql -h localhost -p 5432 -U "$DB_USERNAME" -d emporia

# execution-service rebuilds its venue lifecycle projection from
# order-management before it opens for trading, and fails closed when it cannot
# reach it, so it has to start after order-management is serving.
wait_http_health order-management-service http://localhost:8086/actuator/health

DB_USERNAME="$DB_USERNAME" \
DB_PASSWORD=admin123 \
EXECUTION_VENUE_MODE=${EXECUTION_VENUE_MODE:-exchange-core} \
EXCHANGE_CORE_ACCOUNTING_MODE=${EXCHANGE_CORE_ACCOUNTING_MODE:-full-equity-risk} \
EXCHANGE_CORE_PORTFOLIO_URL=${EXCHANGE_CORE_PORTFOLIO_URL:-http://localhost:8088} \
start_service execution-service execution-service mvn spring-boot:run

SERVER_PORT=8082 \
EMPORIA_AUTH_ISSUER=http://localhost:3001 \
start_service gateway gateway mvn spring-boot:run

wait_http_health static-data-service http://localhost:8081/actuator/health
wait_http_health user-preferences-service http://localhost:8083/actuator/health
wait_http_health market-data-service http://localhost:8084/actuator/health
wait_http_health order-management-service http://localhost:8086/actuator/health
wait_http_health execution-service http://localhost:8087/actuator/health
wait_http_health gateway http://localhost:8082/actuator/health

[ -d frontend/node_modules ] || (cd frontend && npm install)

VITE_GATEWAY_PROXY_TARGET=http://localhost:8082 \
start_service frontend frontend npm run dev -- --port 3001

wait_http_health frontend http://localhost:3001 120

cat <<EOF

==> Local stack is up
    Frontend:         http://localhost:3001  (sign in: admin / admin123)
    Gateway:          http://localhost:8082
    Execution venue:  ${EXECUTION_VENUE_MODE:-exchange-core} (accounting: ${EXCHANGE_CORE_ACCOUNTING_MODE:-full-equity-risk})
    Market data:      ${MARKET_DATA_PROVIDER:-simulated}
    Traces/metrics:   http://localhost:3300  (Grafana: Tempo + Prometheus)
    Logs:             $log_dir
    Stop with:        scripts/stop-services.sh
EOF
