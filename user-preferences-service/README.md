# User preferences service

The user-preferences service owns per-user watchlists and workspace layouts
without mixing them with instruments or market data.

It runs on port `8083` and exposes:

```text
GET    /watchlist
POST   /watchlist/{listingId}
DELETE /watchlist/{listingId}
GET    /workspace-preferences
PUT    /workspace-preferences
```

The gateway publishes these endpoints under `/api/watchlist/**` and
`/api/workspace-preferences`. The service
uses the JWT subject as the user identity and forwards the bearer token to
`static-data-service` when resolving listing details.

It stores listing IDs, display order, creation timestamps, panel order and
visibility, and blotter-column visibility in PostgreSQL.
Quotes remain the responsibility of `market-data-service`, and instrument
metadata remains the responsibility of `static-data-service`.

The default PostgreSQL schema is `emporia_client_config`.

## Configuration

| Environment variable | Default |
|---|---|
| `SERVER_PORT` | `8083` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/english` |
| `DB_SCHEMA` | `emporia_client_config` |
| `EMPORIA_STATIC_DATA_URL` | `http://localhost:8081` |

## Run

```bash
cd emporia/user-preferences-service
mvn spring-boot:run
```
