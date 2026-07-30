# Emporia Authorisation Service

Spring Boot OAuth 2.0 and OpenID Connect authorization server for Emporia. User
accounts are stored in PostgreSQL and passwords are hashed with BCrypt. The
frontend origin at `http://localhost:3000` is its default public issuer; the
frontend development server proxies protocol requests to the gateway.

## Requirements

- Java 21 or newer
- Maven 3.9 or newer
- PostgreSQL running on `localhost:5432`
- Database `emporia` owned by, or accessible to, user `postgres`

## Run locally

Secrets are supplied through environment variables and are not committed:

```bash
cd emporia/authorisation-service
export DB_PASSWORD='your-local-database-password'
export BOOTSTRAP_ADMIN_ENABLED=true
export BOOTSTRAP_ADMIN_USERNAME=admin
export BOOTSTRAP_ADMIN_EMAIL=admin@localhost
export BOOTSTRAP_ADMIN_PASSWORD='replace-with-a-strong-local-password'
mvn spring-boot:run
```

The bootstrap administrator is inserted only when the username does not already
exist. Flyway creates the `emporia_authorisation` schema and database tables
automatically.

## Trading identity

Access and ID tokens include:

- `desk`, used for shared desk order views and cancel-all;
- `can_trade`, enforced by the order command boundary;
- `preferred_username` and the account authorities.

The bootstrap account's trading identity is controlled by
`BOOTSTRAP_ADMIN_DESK` and `BOOTSTRAP_ADMIN_CAN_TRADE`.

## Verify

After starting the frontend and gateway, use the OpenID Connect endpoint on the
frontend origin. The service health endpoint remains available directly on port
`9000`:

```bash
curl http://localhost:9000/actuator/health
curl http://localhost:3000/.well-known/openid-configuration
```

Important protocol endpoints include:

- `/oauth2/authorize`
- `/oauth2/token`
- `/oauth2/revoke`
- `/oauth2/introspect`
- `/oauth2/jwks`
- `/userinfo`

The default local client ID is `emporia-web`. It is a public browser client that
uses Authorization Code with PKCE and has no client secret. Its redirect URI
defaults to `http://localhost:3000/auth/callback` and can be changed with
`OAUTH_REDIRECT_URI`. Spring Authorization Server does not issue refresh tokens
to public clients, so the access token expires after ten minutes and the user
must sign in again.

The service also registers `emporia-market-data`, a confidential machine client
using `client_secret_basic`, the client-credentials grant, and scope `internal`.
The market-data gRPC compatibility endpoint uses it to resolve listing IDs in
`static-data-service` without borrowing a browser token. Configure the same
strong `MARKET_DATA_OAUTH_CLIENT_SECRET` in both services; the repository
default is only for local development.

For standalone development without the gateway, start this service with
`AUTH_ISSUER=http://localhost:9000` and access its endpoints on port `9000`.

## Test

Tests use an in-memory H2 database and do not connect to local PostgreSQL:

```bash
mvn test
```

## Container image

```bash
docker build -t emporia-authorisation-service emporia/authorisation-service
docker run --rm -p 9000:9000 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/emporia \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD='your-local-database-password' \
  -e AUTH_ISSUER=http://localhost:3000 \
  emporia-authorisation-service
```

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `9000` | HTTP listening port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/emporia` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `postgres` | PostgreSQL username |
| `DB_PASSWORD` | none | PostgreSQL password (required) |
| `DB_SCHEMA` | `emporia_authorisation` | Service-owned PostgreSQL schema |
| `AUTH_ISSUER` | `http://localhost:3000` | Public browser-facing issuer URL, proxied to the gateway |
| `OAUTH_CLIENT_ID` | `emporia-web` | Local OAuth client ID |
| `OAUTH_REDIRECT_URI` | frontend callback on port `3000` | Allowed login callback |
| `MARKET_DATA_OAUTH_CLIENT_ID` | `emporia-market-data` | Market-data machine client ID |
| `MARKET_DATA_OAUTH_CLIENT_SECRET` | local development value | Market-data machine client secret |
| `EXECUTION_OAUTH_CLIENT_ID` | `emporia-execution` | Execution machine client ID |
| `EXECUTION_OAUTH_CLIENT_SECRET` | local development value | Execution machine client secret |
| `BOOTSTRAP_ADMIN_ENABLED` | `false` | Create the initial administrator |
| `BOOTSTRAP_ADMIN_USERNAME` | none | Initial administrator username |
| `BOOTSTRAP_ADMIN_EMAIL` | none | Initial administrator email |
| `BOOTSTRAP_ADMIN_PASSWORD` | none | Initial administrator password |
| `BOOTSTRAP_ADMIN_DESK` | `default` | Initial administrator trading desk |
| `BOOTSTRAP_ADMIN_CAN_TRADE` | `true` | Initial administrator trading permission |
