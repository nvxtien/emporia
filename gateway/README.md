# Emporia Gateway

Reactive Spring Cloud Gateway is the browser-facing security and routing
boundary. It proxies OAuth2/OIDC endpoints publicly, validates bearer JWTs for
`/api/**`, preserves the bearer token for defense-in-depth validation by each
backend service, and assigns an `X-Request-Id`.

## Routes

| Public path and method | Upstream |
|---|---|
| `/.well-known/**`, `/oauth2/**`, `/login`, `/logout`, `/userinfo`, `/auth/csrf` | authentication |
| `/api/instruments/**` | static-data-service |
| `/api/watchlist/**` | user-preferences-service |
| `/api/market-data/**` | market-data-service |
| `GET /api/orders/**` | order-management-service |
| `POST /api/orders/**`, including `/api/orders/cancel-all`, and `PUT /api/orders/**` | order-management-service (Disruptor hot path) |
| `/actuator/health/**` | gateway health endpoint |

`/api` is removed before forwarding.

Mutating order routes land on `order-management-service` (Disruptor hot path).
`order-command-service` is an optional Kafka ingress and is not used by gateway.

## Run

Start all upstream services described in `emporia/README.md`, then run:

```bash
cd emporia/gateway
SERVER_PORT=8082 \
EMPORIA_AUTH_ISSUER=http://localhost:3001 \
mvn spring-boot:run
```

## Configuration

| Environment variable | Default |
|---|---|
| `SERVER_PORT` | `8080` |
| `EMPORIA_AUTH_URL` | `http://localhost:9000` |
| `EMPORIA_AUTH_ISSUER` | `http://localhost:3000` |
| `EMPORIA_AUTH_JWK_SET_URI` | `http://localhost:9000/oauth2/jwks` |
| `EMPORIA_STATIC_DATA_URL` | `http://localhost:8081` |
| `EMPORIA_USER_PREFERENCES_URL` | `http://localhost:8083` |
| `EMPORIA_MARKET_DATA_URL` | `http://localhost:8084` |
| `EMPORIA_ORDER_MANAGEMENT_URL` | `http://localhost:8086` |

### Order route resilience

Gateway applies write-path protection only to mutating order routes:

| Route | Protection |
|---|---|
| `POST /api/orders/**` | rate limiter + circuit breaker |
| `PUT /api/orders/**` | rate limiter + circuit breaker |
| `GET /api/orders/**` | no gateway write protection |

Relevant environment variables:

| Environment variable | Default |
|---|---|
| `EMPORIA_GATEWAY_ORDER_RL_REPLENISH_RATE` | `20` |
| `EMPORIA_GATEWAY_ORDER_RL_BURST_CAPACITY` | `40` |
| `EMPORIA_GATEWAY_ORDER_RL_REQUESTED_TOKENS` | `1` |
| `EMPORIA_GATEWAY_ORDER_RL_BYPASS_SERVICE_ACCOUNT_CLAIM` | `service_account=true` |
| `EMPORIA_GATEWAY_ORDER_RL_BYPASS_AUTHORITIES` | empty |
| `EMPORIA_GATEWAY_ORDER_RL_BYPASS_CLAIMS` | empty |
| `EMPORIA_GATEWAY_ORDER_RL_BYPASS_REMOTE_ADDRESSES` | empty |
| `EMPORIA_GATEWAY_ORDER_CB_WINDOW_SIZE` | `2` |
| `EMPORIA_GATEWAY_ORDER_CB_MIN_CALLS` | `2` |
| `EMPORIA_GATEWAY_ORDER_CB_FAILURE_RATE` | `50` |
| `EMPORIA_GATEWAY_ORDER_CB_OPEN_DURATION` | `2s` |
| `EMPORIA_GATEWAY_ORDER_CB_HALF_OPEN_CALLS` | `1` |

When the rate limiter rejects a request, gateway returns `429 Too Many Requests`
with `X-RateLimit-Reason: gateway-order-rate-limit`. When the circuit breaker is
open, gateway returns `503 Service Unavailable` with
`X-Fallback-Reason: gateway-order-circuit-open`.

### Internal service-account token policy

The preferred bypass contract for internal callers is a dedicated JWT claim,
not a subject-name allowlist.

Required policy for privileged internal actors:

1. Set `service_account=true` on the JWT.
2. Optionally add a dedicated authority such as `ROLE_INTERNAL_GATEWAY`.
3. Keep subject values stable for audit, but do not rely on subject text for bypass.

Recommended claim shape:

```json
{
	"sub": "svc-order-router",
	"service_account": true,
	"authorities": ["ROLE_INTERNAL_GATEWAY"]
}
```

Allowed bypass mechanisms, in preferred order:

1. `service_account=true`
2. authority match via `EMPORIA_GATEWAY_ORDER_RL_BYPASS_AUTHORITIES`
3. explicit claim match via `EMPORIA_GATEWAY_ORDER_RL_BYPASS_CLAIMS`
4. remote address match for tightly controlled infrastructure only

Avoid using subject-name bypass rules for new integrations. Subject-based policy
is brittle, hard to rotate, and mixes identity with privilege.

### Resilience observability

Gateway exports these counters:

1. `emporia.gateway.orders.rate_limited`
2. `emporia.gateway.orders.rate_limiter_bypassed`
3. `emporia.gateway.orders.circuit_open`

Prometheus alert rules live in `deploy/otel/alerts/gateway-resilience.yml`, and
the Grafana dashboard is `deploy/otel/dashboards/gateway-resilience.json`.

The issuer is the public browser origin. For the project's normal frontend port
`3001`, set `EMPORIA_AUTH_ISSUER=http://localhost:3001` as shown above.

`/api/market-data/stream` is a long-lived, bearer-authenticated SSE response.
The gateway routes it through the same market-data rule; no WebSocket-specific
gateway configuration is required. Proxy and ingress idle timeouts must exceed
the market-data heartbeat interval (five seconds by default).

## Test

```bash
mvn -f emporia/gateway/pom.xml test
```

The integration test verifies anonymous rejection, JWT validation, token and
request-ID forwarding, OIDC discovery proxying, path rewriting, watchlist
routing, order-route rate limiting, circuit-breaker fallback, and service-account
bypass behavior.
