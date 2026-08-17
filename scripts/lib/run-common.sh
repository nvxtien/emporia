#!/bin/bash
# Shared helpers for scripts/run-local.sh and scripts/run-infra-docker.sh
# (and scripts/stop-services.sh). Sourced, not executed directly. Callers
# must set $repo_root, and start_service()/wait_http_health() also expect
# $log_dir and $pid_dir.

check_exchange_core() {
    echo "==> Checking exchange-core is installed locally"
    if ! mvn -q -f "$repo_root/pom.xml" dependency:get \
            -Dartifact=exchange.core2:exchange-core:0.5.4-SNAPSHOT \
            -o >/dev/null 2>&1; then
        echo "exchange-core is not in your local Maven repository." >&2
        echo "Clone and install it first (see README.md):" >&2
        echo "  git clone https://github.com/nvxtien/exchange-core.git && cd exchange-core && mvn clean install" >&2
        exit 1
    fi
}

# start_service <name> <dir relative to repo root> <command...>
# Backgrounds <command...> run from <dir>, with stdout/stderr captured to
# $log_dir/<name>.log and its pid recorded to $pid_dir/<name>.pid. Any
# VAR=value prefix on the call is exported into the started process only.
start_service() {
    local name="$1" dir="$2"
    shift 2
    echo "==> Starting $name"
    (
        cd "$repo_root/$dir"
        nohup "$@" >"$log_dir/$name.log" 2>&1 < /dev/null &
        echo "$!" >"$pid_dir/$name.pid"
    )
    local pid
    pid="$(cat "$pid_dir/$name.pid")"
    echo "$pid" >"$pid_dir/$name.pid"
    echo "    pid=$pid log=$log_dir/$name.log"
}

# is_descendant <ancestor pid> <pid>
# True when <pid> is <ancestor pid> or was forked from it. Used to tell a
# service this script started from a stranger already holding its port.
is_descendant() {
    local ancestor="$1" pid="$2" hops=0
    while [ -n "$pid" ] && [ "$pid" != "0" ] && [ "$pid" != "1" ]; do
        [ "$pid" = "$ancestor" ] && return 0
        pid="$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')"
        hops=$((hops + 1)); [ "$hops" -gt 24 ] && break
    done
    return 1
}

# verify_port_owner <name> <url>
# A health check only proves *something* answers that port. When a previous run
# is still listening, the service just started fails to bind - and on this stack
# it does not even exit, because exchange-core's non-daemon threads outlive the
# failed context - so the launcher polls the old process, sees it answer, and
# reports a stack that it did not actually start. Every measurement afterwards
# is against unknown code. Confirm the listener descends from the pid recorded
# for this service.
verify_port_owner() {
    local name="$1" url="$2"
    local port owner expected
    # Every skip below is announced. A check that silently passes when it
    # cannot run is the same trap as the health check it exists to backstop.
    port="$(printf '%s' "$url" | sed -n 's|.*://[^:/]*:\([0-9][0-9]*\).*|\1|p')"
    if [ -z "$port" ]; then
        echo "    (cannot read a port from $url; ownership unverified)" >&2; return 0
    fi
    expected="$(cat "$pid_dir/$name.pid" 2>/dev/null)"
    if [ -z "$expected" ]; then
        echo "    (no pid recorded for $name; ownership unverified)" >&2; return 0
    fi
    if ! command -v lsof >/dev/null 2>&1; then
        echo "    (lsof not on PATH; ownership unverified)" >&2; return 0
    fi
    owner="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | head -1)"
    if [ -z "$owner" ]; then
        echo "    $name answered on :$port but nothing is listening there" >&2
        return 1
    fi
    if ! is_descendant "$expected" "$owner"; then
        echo "    $name is NOT the process this script started." >&2
        echo "      :$port is held by pid $owner (started $(ps -o lstart= -p "$owner" 2>/dev/null | xargs))" >&2
        echo "      this run started pid $expected - it most likely failed to bind; see $log_dir/$name.log" >&2
        echo "      stop the old stack first (scripts/stop-services.sh, or by port) and re-run." >&2
        return 1
    fi
    return 0
}

# wait_http_health <name> <url> [timeout seconds, default 90]
wait_http_health() {
    local name="$1" url="$2" timeout="${3:-90}" waited=0
    printf '    waiting for %s' "$name"
    until curl -fsS "$url" >/dev/null 2>&1; do
        if [ "$waited" -ge "$timeout" ]; then
            echo
            echo "    $name did not become healthy within ${timeout}s (see $log_dir/$name.log)" >&2
            return 1
        fi
        printf '.'
        sleep 2
        waited=$((waited + 2))
    done
    if ! verify_port_owner "$name" "$url"; then
        echo
        return 1
    fi
    echo " up"
}

# wait_docker_healthy <compose service name> <compose file> [timeout seconds, default 90]
wait_docker_healthy() {
    local service="$1" compose_file="$2" timeout="${3:-90}" waited=0
    printf '    waiting for %s container' "$service"
    until docker compose -f "$compose_file" ps "$service" 2>/dev/null | grep -q "(healthy)"; do
        if [ "$waited" -ge "$timeout" ]; then
            echo
            echo "    $service did not report healthy within ${timeout}s" >&2
            return 1
        fi
        printf '.'
        sleep 2
        waited=$((waited + 2))
    done
    echo " up"
}

# exchange_core_client_id <username> -- prints the deterministic exchange-core
# client id that ExchangeCoreExecutionVenueGateway#clientId() derives from an
# order's OAuth subject: the positive 64-bit value from
# UUID.nameUUIDFromBytes(username), since the authentication service's JWT
# `sub` claim is the Spring Security principal name (the username). Verified
# against the real JVM implementation, not reimplemented from memory.
exchange_core_client_id() {
    local username="$1"
    if ! command -v python3 >/dev/null 2>&1; then
        echo "python3 not found -- cannot compute the exchange-core client id for '$username'" >&2
        return 1
    fi
    python3 - "$username" <<'PY'
import hashlib
import sys

digest = bytearray(hashlib.md5(sys.argv[1].encode("utf-8")).digest())
digest[6] = (digest[6] & 0x0f) | 0x30
digest[8] = (digest[8] & 0x3f) | 0x80
msb = int.from_bytes(digest[0:8], "big")
print((msb & 0x7fffffffffffffff) or 1)
PY
}

# provision_portfolio_client <username> <psql invocation...> -- idempotently
# seeds a USD (asset 840) balance for <username>'s deterministic exchange-core
# client id so EXCHANGE_CORE_ACCOUNTING_MODE=full-equity-risk can load a risk
# seed for orders that user places. Prints the SQL to run by hand instead of
# failing if psql or python3 aren't on PATH.
provision_portfolio_client() {
    local username="$1"
    shift
    local client_id
    if ! client_id="$(exchange_core_client_id "$username")"; then
        return 0
    fi

    local balance="${PORTFOLIO_SEED_BALANCE:-10000000000}"
    local sql="INSERT INTO emporia_portfolio.portfolio_state (client_id, first_transaction_id)
VALUES ($client_id, 1) ON CONFLICT (client_id) DO NOTHING;
INSERT INTO emporia_portfolio.portfolio_balance (client_id, asset_id, available_balance)
VALUES ($client_id, 840, $balance) ON CONFLICT (client_id, asset_id) DO UPDATE SET available_balance = EXCLUDED.available_balance;"

    if ! command -v psql >/dev/null 2>&1; then
        echo "    psql not found -- run this manually to seed the portfolio balance for '$username' (client $client_id):" >&2
        echo "$sql" | sed 's/^/      /' >&2
        return 0
    fi

    echo "==> Seeding portfolio balance for '$username' (client $client_id, asset 840/USD, balance $balance)"
    echo "$sql" | "$@" -v ON_ERROR_STOP=1 -q
}

# stop_pid_tree <pid> -- stops <pid> and its direct children (e.g. the JVM
# forked by `mvn spring-boot:run`), TERM first, then KILL if still alive.
stop_pid_tree() {
    local pid="$1"
    kill -0 "$pid" 2>/dev/null || return 0
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5; do
        kill -0 "$pid" 2>/dev/null || return 0
        sleep 1
    done
    pkill -KILL -P "$pid" 2>/dev/null || true
    kill -KILL "$pid" 2>/dev/null || true
}
