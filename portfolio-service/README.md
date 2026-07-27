# Portfolio service

The portfolio service owns fully funded cash/equity balances and the
idempotent HTTP receipt boundary used by exchange-core.

It listens on port `8088` by default and owns the PostgreSQL
`emporia_portfolio` schema.

## Internal API

```text
GET /internal/v1/portfolios/{clientId}/risk-seed
PUT /internal/v1/portfolio-snapshots/{deliveryId}/{clientId}
```

The PUT endpoint requires:

```text
Idempotency-Key: {exchangeId}:{deliveryId}:{clientId}
Content-Type: application/json
```

The raw request body and its SHA-256 digest are stored in the same transaction
that replaces the client's available balances. Repeating the same event and
body returns `204` without applying it twice. Reusing the event ID with a
different body returns `409`.

Receipt processing locks the client's `portfolio_state` row. Concurrent
delivery of the same event therefore produces one application and one
successful duplicate without racing two balance replacements. An unknown
client returns `404`, and an invalid path, key, or body returns `400`.

Portfolio clients and their initial balances must be provisioned before
exchange-core calls the risk-seed endpoint. For example:

```sql
INSERT INTO emporia_portfolio.portfolio_state (
    client_id,
    first_transaction_id
) VALUES (101, 1);

INSERT INTO emporia_portfolio.portfolio_balance (
    client_id,
    asset_id,
    available_balance
) VALUES (101, 840, 500000);
```

Authentication uses the normal Emporia OAuth2 resource-server configuration.
The service is internal and is not routed through the browser gateway.

The service currently accepts any valid Emporia bearer token. Production
deployment should also restrict network access and introduce a dedicated
portfolio write scope for exchange instances.

## Run

```bash
cd emporia/portfolio-service
DB_PASSWORD=your-local-db-password \
EMPORIA_AUTH_ISSUER=http://localhost:3001 \
EMPORIA_AUTH_JWK_SET_URI=http://localhost:9000/oauth2/jwks \
mvn spring-boot:run
```

Hikari and Flyway both use `DB_SCHEMA` (default
`emporia_portfolio`). Override the database with `DB_URL`, `DB_USERNAME`, and
`DB_PASSWORD`; do not store production credentials in the repository.

## Verify

Run ordinary unit tests without Docker:

```bash
mvn -pl portfolio-service -am test
```

Run the real PostgreSQL 16 receipt and concurrency specification:

```bash
mvn -Ppostgres-it -pl portfolio-service -am test
```

The opt-in specification verifies atomic balance replacement and receipt
commit, identical redelivery, concurrent duplicate delivery, and rejection of
an idempotency key reused with different content.
