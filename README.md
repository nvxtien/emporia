# Emporia Trading Platform

[![Documentation](https://img.shields.io/badge/Wiki-Documentation-blue?style=for-the-badge&logo=github)](https://github.com/nvxtien/emporia/wiki)
[![JaCoCo Coverage](https://img.shields.io/badge/Coverage-91.95%25-brightgreen?style=for-the-badge)](https://github.com/nvxtien/emporia/wiki/Testing-and-Verification)
[![Java 21](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)](https://github.com/nvxtien/emporia)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0.7-green?style=for-the-badge&logo=springboot)](https://github.com/nvxtien/emporia)
[![React 19](https://img.shields.io/badge/React-19-61DAFB?style=for-the-badge&logo=react)](https://github.com/nvxtien/emporia)

> 📖 **Comprehensive Platform Documentation & Architectural Specifications are published on the [Emporia GitHub Wiki](https://github.com/nvxtien/emporia/wiki)!**

Emporia is an enterprise-grade, distributed stock trading platform built with **Java 21**, **Spring Boot 4.0.7**, **React 19**, **Apache Kafka**, **gRPC**, and **PostgreSQL**. Its deployable boundaries strictly follow business capabilities: static data, user preferences, market data, order command handling, and order management are independent services, with execution routing isolated behind its own service.

---

## 📚 Documentation & GitHub Wiki

For in-depth architectural guides, domain design patterns, microservice deep dives, and trading business logic formulas, explore the **[Emporia GitHub Wiki](https://github.com/nvxtien/emporia/wiki)**:

| Section | Description |
|---|---|
| 📖 **[Trading Terminology Glossary](https://github.com/nvxtien/emporia/wiki/Trading-Terminology-Glossary)** | Financial terms: Order types (Limit, Market, Stop, TIF), BBO/NBBO, SOR, VWAP, P&L. |
| 📐 **[Architecture & Order Flow](https://github.com/nvxtien/emporia/wiki/Architecture-and-Order-Flow)** | System architecture flow, port matrix, REST vs. Kafka EDA, database ownership. |
| 📨 **[Order Command Service](https://github.com/nvxtien/emporia/wiki/Order-Command-Service)** | REST intake, listing snapshot validation, `KafkaCommandGateway` result correlation. |
| ⚙️ **[Order Management Service](https://github.com/nvxtien/emporia/wiki/Order-Management-Service)** | State machine authority, `OrderCommandHandler`, `ExecutionCommandHandler`, idempotency. |
| 🎯 **[Execution Service](https://github.com/nvxtien/emporia/wiki/Execution-Service)** | Algorithmic routing (`DMA`, `SMART` NBBO selector, `VWAP` slicer), venue gateways. |
| 📜 **[Order Lifecycle & Invariants](https://github.com/nvxtien/emporia/wiki/Business-Logic-Order-Lifecycle)** | State machine invariants, tick/lot size checks, late fill accounting. |
| 🧠 **[Order Routing & Execution](https://github.com/nvxtien/emporia/wiki/Business-Logic-Order-Routing-and-Execution)** | Smart Order Routing (SOR) venue splitting & VWAP time-slicing logic. |
| 📊 **[Market Data & Pricing](https://github.com/nvxtien/emporia/wiki/Business-Logic-Market-Data-and-Pricing)** | L1/L2 order books, Price-Time priority matching, micro-price formulas. |
| 💼 **[Portfolio & Risk Controls](https://github.com/nvxtien/emporia/wiki/Business-Logic-Portfolio-and-Risk-Management)** | Long/short positions, cost basis, Mark-to-Market P&L, fat-finger price collars. |
| 🧩 **[Design Patterns Catalog](https://github.com/nvxtien/emporia/wiki/Design-Patterns)** | CQRS, Event-Driven Architecture, Saga, Strategy, State Machine patterns. |
| 📦 **[Microservices Overview](https://github.com/nvxtien/emporia/wiki/Microservices-Overview)** | Deep-dive into all 9 microservices, Gateway, and OAuth2 Authorization. |
| ⚡ **[Exchange-Core Integration](https://github.com/nvxtien/emporia/wiki/Exchange-Core-Integration)** | Ultra-low latency LMAX Disruptor ring-buffer matching engine integration. |
| 🧪 **[Testing & Verification](https://github.com/nvxtien/emporia/wiki/Testing-and-Verification)** | 91.95% JaCoCo coverage, Testcontainers PostgreSQL specs, Fray concurrency tests. |
| 🛠️ **[Deployment & Operations](https://github.com/nvxtien/emporia/wiki/Deployment-and-Operations)** | Environment prerequisites, Docker Compose, Maven builds, React UI startup. |

---

## Architecture

```mermaid
flowchart TD
    Browser[React :3001] -->|OIDC + PKCE / Bearer token| Gateway[Spring Cloud Gateway :8082]
    Gateway --> Auth[Authorisation :9000]
    Gateway --> Static[Static data :8081]
    Gateway --> Preferences[User preferences :8083]
    Gateway --> Market[Market data :8084]
    Gateway --> OrderCommands[Order command service :8085]
    Gateway --> Orders[Order management :8086]
    Execution[Execution service :8087] -->|client credentials| Auth
    ExchangeCore[exchange-core simulation] -->|risk seed + durable snapshots| Portfolio[Portfolio service :8088]
    ExchangeCore -->|bearer token| Auth

    Preferences -->|listing snapshots| Static
    Market -->|listing snapshots| Static
    Market -->|client credentials| Auth
    Fix[FIX simulator gRPC sources] -->|incremental books| Market
    Alpaca[Alpaca IEX] -->|snapshot + WebSocket| Market
    OrderCommands -->|validate listing| Static

    OrderCommands -->|CREATE / MODIFY / CANCEL / CANCEL_ALL| Commands[[emporia.order.commands.v1]]
    Commands --> Orders
    Orders -->|correlated outcome| Results[[emporia.order.results.v1]]
    Results --> OrderCommands
    Orders -->|immutable state events| OrderLog[[emporia.orders.v1]]
    OrderLog --> Execution
    Execution -->|SMART/VWAP child CREATE| Commands
    Execution -->|FILL / REJECT / venue CANCEL| ExecutionCommands[[emporia.execution.commands.v1]]
    ExecutionCommands --> Orders
    Execution -->|recover active parents and children| Orders
    Execution -->|same-instrument listings| Static
    Execution -->|venue quotes| Market

    Auth --> AuthDb[(PostgreSQL\nemporia_authorisation)]
    Static --> StaticDb[(PostgreSQL\nemporia_static_data)]
    Preferences --> PreferencesDb[(PostgreSQL\nemporia_client_config)]
    Orders --> OrderDb[(PostgreSQL\nemporia_order_data)]
    Portfolio --> PortfolioDb[(PostgreSQL\nemporia_portfolio)]
```

The browser sees one `/api` surface. The gateway routes requests by path and
HTTP method to the service that owns each business capability.

## Service ownership

| Directory | Port | Owns |
|---|---:|---|
| `authorisation-service` | 9000 | OAuth2/OIDC login, users, tokens |
| `static-data-service` | 8081 | Instruments and exchange listings |
| `user-preferences-service` | 8083 | Per-user watchlists and persisted workspace layouts |
| `market-data-service` | 8084 HTTP / 50551 gRPC | Simulated, Alpaca IEX, or FIX-simulator market data; venue/composite books; REST, SSE, and gRPC distribution |
| `order-command-service` | 8085 | Authenticated create, modify, cancel, and cancel-all command boundary |
| `order-management-service` | 8086 | Order lifecycle, state, history, executions, and command idempotency |
| `execution-service` | 8087 internal | DMA venue access, best-venue SMART routing, scheduled VWAP child orders, and execution reports |
| `portfolio-service` | 8088 internal | Fully funded cash/equity balances and idempotent exchange snapshot receipts |
| `gateway` | 8082 | Browser security boundary and routing |
| `frontend` | 3001 | React trading workspace |
| `trading-contracts` | not deployed | Versioned Java/Kafka contracts shared at build time |

No running service reads or writes another Emporia service's PostgreSQL schema.
When a service needs listing data, it calls `static-data-service` and forwards
a bearer token. Orders store an immutable listing snapshot instead of a
cross-schema foreign key.

## Kafka order flow

1. `order-command-service` creates a versioned command with a unique
   `commandId` and publishes it to `emporia.order.commands.v1`.
2. `order-management-service` consumes the command, validates the transition, and
   updates its PostgreSQL projection in a transaction.
3. The command result is stored in `processed_order_command`. A redelivered Kafka
   command therefore returns the same result instead of applying the change twice.
4. `order-management-service` publishes the state transition to `emporia.orders.v1`
   and a correlated response to `emporia.order.results.v1`.
5. `order-command-service` completes the waiting browser request. If Kafka or
   the order processor does not answer within eight seconds, it returns a
   timeout or service error rather than pretending the order succeeded.

Commands are keyed by order ID (or user subject for cancel-all). The six Kafka
partitions can process independent orders in parallel while maintaining the order
of commands for one key.

## Execution flow

- DMA sends the order directly to the configured venue gateway. Local development
  uses a deterministic delayed-fill gateway.
- SMART loads every listing for the instrument and walks executable
  opposite-side depth in price/time order. It creates deterministic DMA children
  across venues, observes the parent limit, and waits/retries when liquidity is
  temporarily unavailable.
- VWAP supports absolute start/end seconds and a configurable bucket count. It
  emits increment-aligned catch-up children from the persisted cumulative target,
  parent fills, and active child exposure.
- Child partial and final fills roll up to every parent ancestor atomically with
  weighted-average fill accounting.
- Cancellation is venue-confirmed. A command records a pending
  `targetStatus=CANCELLED`; the venue acknowledgement finalizes it, while a
  racing execution remains valid.
- `EXECUTION_VENUE_MODE=fix` enables the built-in FIXT 1.1 / FIX 5.0 SP2 source
  adapter for new, modify, cancel, and execution-report messages.
- New execution consumers start at the latest order event, so introducing the
  service cannot accidentally execute retained historical orders.
- On restart, execution rebuilds direct-order and SMART/VWAP runtimes from the
  order-management PostgreSQL projection rather than replaying creates.

See the [DMA, SMART, and VWAP execution guide](docs/execution/README.md) for
strategy behavior, order examples, cancellation, recovery, and current
boundaries. Service-level configuration is collected in
[execution-service/README.md](execution-service/README.md).

## Local prerequisites

- Java 21 or newer
- Maven 3.9+
- Node.js and npm
- Docker with Compose
- PostgreSQL running at `localhost:5432`

Local PostgreSQL settings:

- Database: `emporia`
- Username: `postgres`
- Password: `admin123`

Flyway creates these schemas: `emporia_authorisation`, `emporia_static_data`,
`emporia_client_config`, `emporia_order_data`, and `emporia_portfolio`.

## Start locally

Run all commands from the repository root. Keep each long-running Maven or npm
command in its own terminal.

1. Start Kafka:

   ```bash
   docker compose -f emporia/compose.kafka.yml up -d
   docker compose -f emporia/compose.kafka.yml ps
   ```

2. Build and install the shared Kafka contract and all split services:

   ```bash
   mvn -f emporia/pom.xml install
   ```

3. Start the authorisation service:

   ```bash
   cd emporia/authorisation-service
   SERVER_PORT=9000 \
   AUTH_ISSUER=http://localhost:3001 \
   OAUTH_REDIRECT_URI=http://localhost:3001/auth/callback \
   OAUTH_POST_LOGOUT_REDIRECT_URI=http://localhost:3001/auth/logout-callback \
   BOOTSTRAP_ADMIN_ENABLED=true \
   BOOTSTRAP_ADMIN_USERNAME=admin \
   BOOTSTRAP_ADMIN_EMAIL=admin@localhost \
   BOOTSTRAP_ADMIN_PASSWORD=admin123 \
   BOOTSTRAP_ADMIN_DESK=default \
   BOOTSTRAP_ADMIN_CAN_TRADE=true \
   DB_PASSWORD=admin123 \
   mvn spring-boot:run
   ```

4. Start the seven trading microservices, one command per terminal:

   ```bash
   cd emporia/static-data-service && mvn spring-boot:run
   ```

   To optionally import active US-equity reference data or connect to Alpaca's real-time IEX feed:

   ```bash
   cd emporia/market-data-service
   MARKET_DATA_PROVIDER=alpaca-iex \
   APCA_API_KEY_ID=your-alpaca-key-id \
   APCA_API_SECRET_KEY=your-alpaca-secret-key \
   mvn spring-boot:run
   ```

   *Credentials are read directly from process environment variables and are never persisted to disk or databases. See [docs/market-data/README.md](docs/market-data/README.md) for details.*

   ```bash
   cd emporia/user-preferences-service && mvn spring-boot:run
   ```

   ```bash
   cd emporia/market-data-service && mvn spring-boot:run
   ```

   The market-data service uses its deterministic simulator by default. It also
   exposes a browser SSE stream on port `8084` and the
   `marketdataservice.MarketDataService` gRPC interface on port `50551`.

   To consume incremental order books directly from one or more FIX simulator
   gRPC sources:

   ```bash
   cd emporia/market-data-service
   MARKET_DATA_PROVIDER=fix-simulator \
   FIX_SIMULATOR_CONNECTIONS='XNAS=localhost:50051,XNYS=localhost:50052' \
   mvn spring-boot:run
   ```

   Multiple replicas for one venue are separated with `|`, for example
   `XNAS=nasdaq-a:50051|nasdaq-b:50051`. Listings are assigned
   deterministically across those replicas. The provider reconnects,
   resubscribes, maintains entries by FIX entry ID, and applies
   `NEW`, `CHANGE`, `OVERLAY`, and `DELETE` updates.

   Browser clients use `GET /api/market-data/stream` and receive conflated
   continuous quotes. Snapshot REST endpoints remain available for compatibility.
   Composite listings use exchange MIC `XOSR`; the service combines all
   same-symbol venue books while retaining each level's source listing and MIC.

   See the [market-data service runbook](market-data-service/README.md) for
   configuration, observability, and deployment details.

   ```bash
   cd emporia/order-command-service && mvn spring-boot:run
   ```

   `order-command-service` owns create, modify, single-cancel, and cancel-all
   HTTP commands. There is no separate `order-monitor` process or port.

   ```bash
   cd emporia/order-management-service && mvn spring-boot:run
   ```

   ```bash
   cd emporia/execution-service && mvn spring-boot:run
   ```

   The execution service uses delayed simulated venue fills by default. Its
   OAuth client is registered automatically by the local authorisation
   configuration. Use the same `EXECUTION_OAUTH_CLIENT_SECRET` value in both
   services when overriding the local default.

   ```bash
   cd emporia/portfolio-service && mvn spring-boot:run
   ```

   The portfolio service is an internal exchange accounting boundary; the
   browser gateway does not route to it. Provision clients before exchange-core
   requests a risk seed. See
   [portfolio-service/README.md](portfolio-service/README.md) for its API,
   idempotency contract, and PostgreSQL integration test.

5. Start the gateway on the browser's proxy port:

   ```bash
   cd emporia/gateway
   SERVER_PORT=8082 \
   EMPORIA_AUTH_ISSUER=http://localhost:3001 \
   mvn spring-boot:run
   ```

6. Start React:

   ```bash
   cd emporia/frontend
   npm install
   VITE_GATEWAY_PROXY_TARGET=http://localhost:8082 npm run dev -- --port 3001
   ```

7. Open `http://localhost:3001` and sign in with `admin` / `admin123`.

Every service supports `GET /actuator/health` without a token. Kafka is healthy
when `docker compose -f emporia/compose.kafka.yml ps` reports `healthy`.

## Verify

Compile, test, and run PMD for every service, then verify the frontend:

```bash
mvn -f emporia/pom.xml verify
mvn -f emporia/authorisation-service/pom.xml verify
mvn -f emporia/gateway/pom.xml verify
npm --prefix emporia/frontend run lint
npm --prefix emporia/frontend run build
npm --prefix emporia/frontend run test:e2e
```

The Maven `verify` phase runs PMD 7.26.0 through Maven PMD Plugin 3.28.0 and
fails on violations or PMD processing errors. The shared
[`static-analysis/ruleset.xml`](static-analysis/ruleset.xml) concentrates on correctness,
resource ownership, exception integrity, concurrency, and unambiguous
performance problems; it intentionally excludes formatting and subjective
style checks. Generated sources and test sources are excluded.

To generate browsable PMD reports without running the full build:

```bash
mvn -f emporia/pom.xml -DskipTests pmd:pmd
mvn -f emporia/authorisation-service/pom.xml -DskipTests pmd:pmd
mvn -f emporia/gateway/pom.xml -DskipTests pmd:pmd
```

Each module writes XML to `target/pmd.xml` and HTML to
`target/reports/pmd.html`.

Run only the jqwik order invariants:

```bash
mvn -f emporia/pom.xml -pl order-management-service -am \
  -Dtest=TradingOrderPropertyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The generated properties cover positive and increment-aligned quantities,
positive tick-aligned limit prices, partial-fill quantity accounting,
modifications bounded by traded quantity, two-fill weighted averages,
randomized command and venue-event sequences, pending cancellation,
cancel-versus-fill races in both arrival orders, late fills after venue
acknowledgement, and idempotent command redelivery.

Run the real PostgreSQL optimistic-lock race with Testcontainers:

```bash
mvn -f emporia/pom.xml -Ppostgres-it -pl order-management-service -am test
```

This opt-in test applies Flyway migrations to PostgreSQL 16 and races two
independent transactions that loaded the same entity version. See the
[order-management service documentation](order-management-service/README.md#postgresql-concurrency-test)
for the OrbStack command and assertions.

Run the portfolio receipt idempotency and concurrency tests with PostgreSQL:

```bash
mvn -f emporia/pom.xml -Ppostgres-it -pl portfolio-service -am test
```

Run the controlled cancel-versus-full-fill concurrency pilot with Fray:

```bash
mvn -f emporia/pom.xml -Pfray -pl order-management-service -am test
```

The profile adds the isolated Fray source set, runs only its pilot test, and
leaves ordinary `mvn test` unchanged. See the
[order-management service documentation](order-management-service/README.md#controlled-concurrency-pilot)
for its scope and limitations.

Model-check the proposed fill/cancel state machine with TLC:

```bash
cd emporia/verification/order-lifecycle
java -XX:+UseParallelGC -jar /path/to/tla2tools.jar \
  -config OrderLifecycle.cfg \
  -metadir target/tlc \
  OrderLifecycle.tla
```

See the [order lifecycle TLA+ model](verification/order-lifecycle/README.md) for the
checked invariants, race semantics, and model boundaries.

Java aggregate validation and Flyway migrations `V2` and `V4` enforce the
corresponding persisted-state and pending-cancellation invariants. See the
[order-management invariant documentation](order-management-service/README.md) for the
constraint matrix, migration behavior, and focused test commands.

With the application running, execute the full OIDC and Kafka smoke test:

```bash
EMPORIA_ORIGIN=http://localhost:3001 \
EMPORIA_USERNAME=admin \
EMPORIA_PASSWORD=admin123 \
node emporia/scripts/oidc-smoke-test.mjs
```

The check signs in with Authorization Code + PKCE, verifies instruments,
watchlist, quotes, and the order blotter, then exercises a DMA cancel/fill race,
depth-aware SMART, scheduled VWAP, materialized history, and
order-command-service cancel-all through the live Kafka flow.

## Stop locally

Stop each foreground Spring Boot/npm process with `Ctrl+C`, then stop Kafka:

```bash
docker compose -f emporia/compose.kafka.yml down
```

Do not add `-v` unless you intentionally want to delete the local Kafka volume.
