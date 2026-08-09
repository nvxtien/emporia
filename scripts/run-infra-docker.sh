#!/bin/bash
# Run mode 2 (Infrastructure-only Docker), automated: every Spring service
# in the host JVM against per-service PostgreSQL containers and a
# Dockerized Kafka broker -- see the "Infrastructure-Only Docker Setup"
# section in README.md for the port map this script uses.
#
# execution-service defaults to EXECUTION_VENUE_MODE=exchange-core; export a
# different value before running this script to override. market-data-service
# defaults to MARKET_DATA_PROVIDER=simulated; export MARKET_DATA_PROVIDER=alpaca-iex
# plus APCA_API_KEY_ID/APCA_API_SECRET_KEY to use live Alpaca IEX data instead.
#
# Stop the Spring services with scripts/stop-services.sh, then
# `docker compose down` to stop the containers (add -v only if you want to
# delete the per-service database volumes).
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

echo "==> Starting per-service PostgreSQL containers and Kafka (Docker)"
docker compose up -d
for c in authentication-postgres static-data-postgres user-preferences-postgres \
         order-management-postgres execution-postgres portfolio-postgres kafka kafka-connect; do
    wait_docker_healthy "$c" docker-compose.yml
done

# clean is required: the protobuf-maven-plugin has produced inconsistent
# incremental builds against a stale target/ from an earlier run.
echo "==> Building and installing reactor modules (mvn clean install -DskipTests)"
mvn -q -f pom.xml clean install -DskipTests

AUTH_ISSUER=http://localhost:3001 \
OAUTH_REDIRECT_URI=http://localhost:3001/auth/callback \
OAUTH_POST_LOGOUT_REDIRECT_URI=http://localhost:3001/auth/logout-callback \
BOOTSTRAP_ADMIN_ENABLED=true \
BOOTSTRAP_ADMIN_USERNAME="$BOOTSTRAP_ADMIN_USERNAME" \
BOOTSTRAP_ADMIN_EMAIL=admin@localhost \
BOOTSTRAP_ADMIN_PASSWORD=admin123 \
BOOTSTRAP_ADMIN_DESK=default \
BOOTSTRAP_ADMIN_CAN_TRADE=true \
DB_URL=jdbc:postgresql://localhost:5433/emporia_authentication \
DB_PASSWORD=admin123 \
start_service authentication authentication mvn spring-boot:run

wait_http_health authentication http://localhost:9000/actuator/health

DB_URL=jdbc:postgresql://localhost:5434/emporia_static_data \
DB_PASSWORD=admin123 \
start_service static-data-service static-data-service mvn spring-boot:run

DB_URL=jdbc:postgresql://localhost:5435/emporia_user_preferences \
DB_PASSWORD=admin123 \
start_service user-preferences-service user-preferences-service mvn spring-boot:run

start_service market-data-service market-data-service mvn spring-boot:run

DB_URL=jdbc:postgresql://localhost:5436/emporia_order_management \
DB_PASSWORD=admin123 \
start_service order-management-service order-management-service mvn spring-boot:run

DB_URL=jdbc:postgresql://localhost:5438/emporia_portfolio \
DB_PASSWORD=admin123 \
start_service portfolio-service portfolio-service mvn spring-boot:run

wait_http_health portfolio-service http://localhost:8088/actuator/health
PGPASSWORD=admin123 provision_portfolio_client "$BOOTSTRAP_ADMIN_USERNAME" psql -h localhost -p 5438 -U postgres -d emporia_portfolio

# execution-service rebuilds its venue lifecycle projection from
# order-management before it opens for trading, and fails closed when it cannot
# reach it - opening with an empty projection would treat redelivered commands
# as new ones. It therefore has to start after order-management is serving, not
# merely after it has been launched.
wait_http_health order-management-service http://localhost:8086/actuator/health

# CDC-drains order_outbox after Flyway has created the table, but before
# execution/gateway/frontend start accepting traffic.
echo "==> Registering the order_outbox CDC connector"
"$repo_root/scripts/register-outbox-connector.sh"

DB_URL=jdbc:postgresql://localhost:5437/emporia_execution \
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

==> Infrastructure-only Docker stack is up
    Frontend:         http://localhost:3001  (sign in: admin / admin123)
    Gateway:          http://localhost:8082
    Execution venue:  ${EXECUTION_VENUE_MODE:-exchange-core} (accounting: ${EXCHANGE_CORE_ACCOUNTING_MODE:-full-equity-risk})
    Market data:      ${MARKET_DATA_PROVIDER:-simulated}
    Traces/metrics:   http://localhost:3300  (Grafana: Tempo + Prometheus)
    Postgres/Kafka:   docker compose ps
    Logs:             $log_dir
    Stop Spring services with: scripts/stop-services.sh
    Stop containers with:      docker compose down
EOF
