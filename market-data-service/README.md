# Emporia Market Data Service

This service is the complete Emporia market-data boundary. It owns source
connections, live book state, cross-venue aggregation, browser delivery, and
the streaming gRPC interface.

## Interfaces

| Interface | Address | Purpose |
|---|---|---|
| REST snapshot | `GET :8084/market-data/quotes?listingIds=...` | Batch compatibility and diagnostics |
| REST depth | `GET :8084/market-data/{listingId}/depth` | One listing snapshot |
| SSE | `GET :8084/market-data/stream?listingIds=...` | Continuous browser quotes and depth |
| gRPC | `:50551 marketdataservice.MarketDataService` | Streaming `Connect` and `Subscribe` |
| Prometheus | `GET :8084/actuator/prometheus` | Stream and provider metrics |

HTTP endpoints require a user bearer token. The gRPC bridge obtains its own
`emporia-market-data` client-credentials token before resolving a subscribed
listing in `static-data-service`.

The SSE publisher is shared across clients. Each subscriber keeps at most the
latest pending quote per listing, so a slow browser or gRPC consumer does not
create an unbounded queue. Unchanged snapshots are suppressed and a heartbeat
is emitted at the configured interval.

## Providers

Set `MARKET_DATA_PROVIDER` to one of:

- `simulated` (default): deterministic five-level development books.
- `alpaca-iex`: Alpaca free IEX snapshots, quotes, and trades. This is real
  top-of-book data for IEX, not a consolidated US market book.
- `fix-simulator`: direct bidirectional gRPC connections to the repository's
  FIX simulators.

### Alpaca IEX

```bash
MARKET_DATA_PROVIDER=alpaca-iex \
APCA_API_KEY_ID='<key-id>' \
APCA_API_SECRET_KEY='<secret>' \
mvn spring-boot:run
```

The credentials are process environment variables and are never persisted by
the application. Inject them through a secret manager outside local
development.

### FIX simulator

```bash
MARKET_DATA_PROVIDER=fix-simulator \
FIX_SIMULATOR_CONNECTIONS='XNAS=nasdaq-a:50051|nasdaq-b:50051,XNYS=nyse:50051' \
mvn spring-boot:run
```

The configuration maps an exchange MIC to one or more gRPC addresses. A source
listing is routed to one replica deterministically by listing ID. The service
reconnects and resubscribes after failure, maintains the full book by entry ID,
and propagates an explicit interrupted state while a source is unavailable.

## Composite books

Static data includes one `XOSR` listing for each seeded symbol. Requesting an
`XOSR` listing resolves all non-`XOSR` listings with the same symbol and
combines their books:

- bids sort from highest to lowest;
- offers sort from lowest to highest;
- each depth level retains `exchangeMic`, `entryId`, and source `listingId`;
- volume is summed and the most recent venue trade supplies last price/size;
- any source interruption is visible in `streamInterrupted` and
  `streamStatusMessage`.

No cross-service database read is used. Resolution goes through
`static-data-service`.

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8084` | HTTP/SSE port |
| `MARKET_DATA_GRPC_ENABLED` | `true` | Enable the gRPC server |
| `MARKET_DATA_GRPC_PORT` | `50551` | Compatibility gRPC port |
| `MARKET_DATA_PUBLISH_INTERVAL` | `250ms` | Source snapshot/publish cadence |
| `MARKET_DATA_HEARTBEAT_INTERVAL` | `5s` | Maximum silence for unchanged data |
| `EMPORIA_STATIC_DATA_URL` | `http://localhost:8081` | Listing service |
| `EMPORIA_AUTH_TOKEN_URL` | `http://localhost:9000/oauth2/token` | Machine token endpoint |
| `MARKET_DATA_OAUTH_CLIENT_ID` | `emporia-market-data` | Confidential service client |
| `MARKET_DATA_OAUTH_CLIENT_SECRET` | local development value | Must match authorisation service |
| `FIX_SIMULATOR_CLIENT_ID` | `emporia-market-data` | Upstream stream client identity |
| `FIX_SIMULATOR_CONNECTIONS` | empty | MIC-to-source routing map |
| `FIX_SIMULATOR_INITIAL_DATA_TIMEOUT` | `5s` | First-book timeout |
| `FIX_SIMULATOR_RECONNECT_DELAY` | `5s` | Source reconnect delay |
| `APCA_API_KEY_ID` | empty | Alpaca key ID |
| `APCA_API_SECRET_KEY` | empty | Alpaca secret |
| `ALPACA_MAX_SYMBOLS` | `30` | Alpaca subscription cap |

The local OAuth secret is for development only. Use the same
`MARKET_DATA_OAUTH_CLIENT_SECRET` value in the authorisation and market-data
processes and inject a strong secret in shared environments.

## Observability

The Prometheus endpoint includes:

- `emporia_market_data_quotes_published_total`
- `emporia_market_data_quotes_conflated_total`
- `emporia_market_data_provider_failures_total`
- `emporia_market_data_subscribers`

`GET /actuator/health` also reports the selected provider. A quote can remain
present while carrying `streamInterrupted=true`; consumers should display its
status rather than silently treating stale data as live.

## Build and test

The protobuf build generates the market-data consumer contract and Java stubs
from the repository FIX simulator contract:

```bash
mvn -f emporia/pom.xml -pl market-data-service -am clean test
npm --prefix emporia/frontend run build
```

Build the image from the repository root because the FIX protobuf sources are
shared:

```bash
docker build -f emporia/market-data-service/Dockerfile \
  -t emporia-market-data-service .
```

The Kubernetes deployment is
[`../deploy/market-data-service.yaml`](../deploy/market-data-service.yaml).
Its service uses `sessionAffinity: ClientIP` because a client opens a streaming
`Connect` call and sends later `Subscribe` calls that must reach the same
replica.
