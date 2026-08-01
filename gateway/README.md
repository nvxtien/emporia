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
| `POST /api/orders/**`, including `/api/orders/cancel-all`, and `PUT /api/orders/**` | order-command-service |
| `/actuator/health/**` | gateway health endpoint |

`/api` is removed before forwarding.

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
| `EMPORIA_ORDER_COMMAND_URL` | `http://localhost:8085` |
| `EMPORIA_ORDER_MANAGEMENT_URL` | `http://localhost:8086` |

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
routing, and `POST /api/orders/cancel-all` routing to
`order-command-service`.
