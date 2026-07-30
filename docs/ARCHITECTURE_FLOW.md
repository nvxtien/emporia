# Emporia Trading Platform — Architecture & Main Flow Documentation

Emporia is an event-driven, microservices-based trading platform built on **Spring Boot**, **Kafka**, **PostgreSQL**, and **React**. Its services communicate asynchronously via versioned Kafka event schemas (`trading-contracts`), while maintaining strict service ownership boundaries.

---

## 1. System Architecture Diagram

```mermaid
flowchart TD
    subgraph Client Layer
        Browser["React Trading UI :3001"]
    end

    subgraph Security Boundary
        Gateway["Spring Cloud Gateway :8082"]
        Auth["Authorisation Service :9000"]
        Browser --> Gateway
        Gateway --> Auth
    end

    subgraph Core Trading Services
        Gateway --> Static["static-data-service :8081"]
        Gateway --> Preferences["user-preferences-service :8083"]
        Gateway --> Market["market-data-service :8084"]
        Gateway --> OrderCmd["order-command-service :8085"]
        Gateway --> Orders["order-management-service :8086"]
    end

    subgraph Kafka Event Backplane
        OrderCmd -->|1. OrderCommand| KafkaCmd["Kafka: emporia.order.commands.v1"]
        KafkaCmd --> Orders
        Orders -->|2. OrderDomainEvent| KafkaEvt["Kafka: emporia.orders.v1"]
        Orders -->|3. OrderCommandResult| KafkaRes["Kafka: emporia.order.results.v1"]
        KafkaRes --> OrderCmd
    end

    subgraph Execution & Routing Engine
        KafkaEvt --> Execution["execution-service :8087"]
        Market -->|gRPC Quotes| Execution
        Execution -->|Child OrderCommands| KafkaCmd
        Execution -->|ExecutionCommands| KafkaExeCmd["Kafka: emporia.execution.commands.v1"]
        KafkaExeCmd --> Orders
    end

    subgraph Execution Venues
        Execution -->|exchange-core LMAX| ExCore["Exchange Core Engine"]
        Execution -->|FIXT 1.1 / FIX 5.0| FIX["FIX Broker / Simulator"]
        ExCore --> Portfolio["portfolio-service :8088"]
    end

    subgraph Isolated Persistence
        Static --> StaticDb[("emporia_static_data")]
        Preferences --> PrefDb[("emporia_client_config")]
        Orders --> OrderDb[("emporia_order_data")]
        Portfolio --> PortDb[("emporia_portfolio")]
        Auth --> AuthDb[("emporia_authorisation")]
    end
```

---

## 2. Microservice Responsibility Matrix

| Microservice | Port | Primary Responsibility | Key Data Ownership |
|---|---:|---|---|
| **`authorisation-service`** | `9000` | OAuth2 / OpenID Connect provider, user authentication, desk permissions, client credentials token issuer. | `emporia_authorisation` |
| **`static-data-service`** | `8081` | Reference data master, asset & listing search, Alpaca asset master importer. | `emporia_static_data` |
| **`user-preferences-service`** | `8083` | User-configured watchlists and workspace UI layout configurations. | `emporia_client_config` |
| **`market-data-service`** | `8084` (HTTP)<br>`50551` (gRPC) | Conflated top-of-book market quotes, depth, Alpaca IEX & FIX simulator adapters, SSE/gRPC streaming. | Memory Cache & Stream Subscriptions |
| **`order-command-service`** | `8085` | Ingress boundary for client order commands (`CREATE`, `MODIFY`, `CANCEL`, `CANCEL_ALL`), listing validation, command correlation. | Command correlation maps |
| **`order-management-service`** | `8086` | State engine, order lifecycle, PostgreSQL order projections, command idempotency, SSE blotter streaming. | `emporia_order_data` |
| **`execution-service`** | `8087` | Smart Order Routing (`DMA`, `SMART`, `VWAP`), venue selection (`BestVenueSelector`), `exchange-core` & FIX venue gateways. | Strategy runtimes & checkpoints |
| **`portfolio-service`** | `8088` | Fully funded cash and asset balance accounting, idempotent snapshot receipts for exchange engines. | `emporia_portfolio` |
| **`trading-contracts`** | N/A | Build-time library defining versioned Java records for Kafka topics. | Shared Java Domain Contracts |

---

## 3. End-to-End Main Order Lifecycle Flow

### Step 1: Market Data Broadcasting
1. **`market-data-service`** ingests market quotes from Alpaca IEX or FIX Simulators.
2. Market data is conflated and broadcasted via **SSE** to the frontend trading blotter and via **gRPC** to `execution-service`.

### Step 2: Order Command Ingress
1. Trader creates an order (e.g. `BUY 100 AAPL @ 150.00 LIMIT`) in the React UI.
2. The request hits **`order-command-service`** (`POST /api/orders`).
3. `order-command-service` validates the target instrument with **`static-data-service`** and constructs an `OrderCommand`.
4. It publishes `OrderCommand` to Kafka topic `emporia.order.commands.v1`.

### Step 3: Order State Transition & Event Publication
1. **`order-management-service`** consumes the command from `emporia.order.commands.v1`.
2. It executes a database transaction on `emporia_order_data`:
   - Validates command idempotency via `processed_order_command`.
   - Inserts order in state `PENDING` -> `LIVE`.
3. Publishes `OrderDomainEvent` (`CREATED`) to `emporia.orders.v1` and correlation result to `emporia.order.results.v1`.
4. Emits real-time SSE updates to the React UI order blotter.

### Step 4: Routing Strategy Execution
**`execution-service`** consumes the `OrderDomainEvent` (`CREATED`):
- **DMA Routing**: Passes the order directly to the designated venue gateway (`ExchangeCoreExecutionVenueGateway`, `FixExecutionVenueGateway`, or `SimulatedExecutionVenueGateway`).
- **SMART Routing**: `BestVenueSelector` queries `market-data-service` for available top-of-book depth across matching exchange listings, slices the parent order, and dispatches child `OrderCommand` messages back to `emporia.order.commands.v1`.
- **VWAP Routing**: Schedules slice executions based on participation rate and bucket parameters.

### Step 5: Trade Execution, Fills, & Portfolio Settlement
1. The execution venue (e.g., **`exchange-core`** Disruptor matching engine or FIX broker) matches orders and generates fills (`DmaFill` / `ExecutionReport`).
2. Gateway converts fills into `ExecutionCommand` and publishes to `emporia.execution.commands.v1`.
3. `order-management-service` consumes fills, atomically updates order state to `FILLED` / `PARTIALLY_FILLED`, updates weighted average fill price, and notifies **`portfolio-service`** to adjust trader cash & asset balances.
