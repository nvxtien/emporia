#!/bin/bash
# Run mode 2 (Infrastructure-only Docker), automated: every Spring service
# in the host JVM against per-service PostgreSQL containers -- see the
# "Infrastructure-Only Docker Setup" section in README.md for the port map
# this script uses.
#
# order-management-service (execution routing merged in) defaults to
# EXECUTION_VENUE_MODE=exchange-core; export a different value before running
# this script to override. market-data-service defaults to
# MARKET_DATA_PROVIDER=simulated; export MARKET_DATA_PROVIDER=alpaca-iex plus
# APCA_API_KEY_ID/APCA_API_SECRET_KEY to use live Alpaca IEX data instead.
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
MAVEN_TEST_SKIP_ARGS=(${MAVEN_TEST_SKIP_ARGS:--DskipTests})

echo "==> Starting per-service PostgreSQL containers (Docker)"
docker compose up -d
for c in authentication-postgres static-data-postgres user-preferences-postgres \
         order-management-postgres execution-postgres portfolio-postgres; do
    wait_docker_healthy "$c" docker-compose.yml
done

# clean is required: the protobuf-maven-plugin has produced inconsistent
# incremental builds against a stale target/ from an earlier run.
echo "==> Building and installing reactor modules (mvn clean install ${MAVEN_TEST_SKIP_ARGS[*]})"
# -Dmatching: this stack runs EXECUTION_VENUE_MODE=exchange-core, which the
# default agency artifact deliberately cannot serve.
mvn -q -f pom.xml clean install -Dmatching "${MAVEN_TEST_SKIP_ARGS[@]}"

# Ten minutes suits a browser session. A soak test mints once and cannot renew
# - this client only offers authorization-code + PKCE - so a run longer than the
# lifetime collects 4xx that order-load.js counts as business rejections and
# aborts on. Export a longer one for such a run:
#   OAUTH_ACCESS_TOKEN_TTL=4h scripts/run-infra-docker.sh
OAUTH_ACCESS_TOKEN_TTL="${OAUTH_ACCESS_TOKEN_TTL:-10m}" \
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
start_service authentication authentication mvn "${MAVEN_TEST_SKIP_ARGS[@]}" spring-boot:run

wait_http_health authentication http://localhost:9000/actuator/health

DB_URL=jdbc:postgresql://localhost:5434/emporia_static_data \
DB_PASSWORD=admin123 \
start_service static-data-service static-data-service mvn "${MAVEN_TEST_SKIP_ARGS[@]}" spring-boot:run

DB_URL=jdbc:postgresql://localhost:5435/emporia_user_preferences \
DB_PASSWORD=admin123 \
start_service user-preferences-service user-preferences-service mvn "${MAVEN_TEST_SKIP_ARGS[@]}" spring-boot:run

start_service market-data-service market-data-service mvn "${MAVEN_TEST_SKIP_ARGS[@]}" spring-boot:run

DB_URL=jdbc:postgresql://localhost:5436/emporia_order_management \
DB_PASSWORD=admin123 \
EXECUTION_VENUE_MODE=${EXECUTION_VENUE_MODE:-exchange-core} \
EXCHANGE_CORE_ACCOUNTING_MODE=${EXCHANGE_CORE_ACCOUNTING_MODE:-full-equity-risk} \
EXCHANGE_CORE_PORTFOLIO_URL=${EXCHANGE_CORE_PORTFOLIO_URL:-http://localhost:8088} \
EXCHANGE_CORE_JOURNALING=${EXCHANGE_CORE_JOURNALING:-false} \
EXCHANGE_CORE_SNAPSHOT_INTERVAL=${EXCHANGE_CORE_SNAPSHOT_INTERVAL:-60s} \
EXCHANGE_CORE_RETAINED_CHECKPOINTS=${EXCHANGE_CORE_RETAINED_CHECKPOINTS:-2} \
EXCHANGE_CORE_MIN_FREE_STORAGE_BYTES=${EXCHANGE_CORE_MIN_FREE_STORAGE_BYTES:-0} \
JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=1024m --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-exports=java.base/jdk.internal.util=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED" \
start_service order-management-service order-management-service mvn -Dmatching "${MAVEN_TEST_SKIP_ARGS[@]}" spring-boot:run

DB_URL=jdbc:postgresql://localhost:5438/emporia_portfolio \
DB_PASSWORD=admin123 \
start_service portfolio-service portfolio-service mvn "${MAVEN_TEST_SKIP_ARGS[@]}" spring-boot:run

wait_http_health portfolio-service http://localhost:8088/actuator/health
PGPASSWORD=admin123 provision_portfolio_client "$BOOTSTRAP_ADMIN_USERNAME" psql -h localhost -p 5438 -U postgres -d emporia_portfolio

SERVER_PORT=8082 \
EMPORIA_AUTH_ISSUER=http://localhost:3001 \
start_service gateway gateway mvn "${MAVEN_TEST_SKIP_ARGS[@]}" spring-boot:run

wait_http_health static-data-service http://localhost:8081/actuator/health
wait_http_health user-preferences-service http://localhost:8083/actuator/health
wait_http_health market-data-service http://localhost:8084/actuator/health
wait_http_health order-management-service http://localhost:8086/actuator/health
wait_http_health gateway http://localhost:8082/actuator/health

[ -d frontend/node_modules ] || (cd frontend && npm install)

VITE_GATEWAY_PROXY_TARGET=http://localhost:8082 \
start_service frontend frontend npm run dev -- --port 3001

wait_http_health frontend http://localhost:3001 120

cat <<EOF

==> Infrastructure-only Docker stack is up
    Frontend:         http://localhost:3001  (sign in: admin / admin123)
    Gateway:          http://localhost:8082
    Execution venue:  ${EXECUTION_VENUE_MODE:-exchange-core} (accounting: ${EXCHANGE_CORE_ACCOUNTING_MODE:-full-equity-risk}, journaling: ${EXCHANGE_CORE_JOURNALING:-false})
    Market data:      ${MARKET_DATA_PROVIDER:-simulated}
    Traces/metrics:   http://localhost:3300  (Grafana: Tempo + Prometheus)
    Postgres:         docker compose ps
    Logs:             $log_dir
    Stop Spring services with: scripts/stop-services.sh
    Stop containers with:      docker compose down
EOF
