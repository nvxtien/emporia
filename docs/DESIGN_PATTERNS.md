# Software Design Patterns in the Emporia Trading Platform

This document details the software design patterns used across the **Emporia Trading Platform** architecture, split by domain and design intent.

---

## 1. Architectural & Distributed Systems Patterns

### API Gateway Pattern
- **Component**: `gateway` (Spring Cloud Gateway on port `8082`).
- **Implementation**: Single reverse-proxy entry point for frontend requests (`/api/*`).
- **Benefits**: Decouples the React SPA from internal microservices, handles CORS, routes by path/method, and enforces OAuth2/OIDC PKCE token validation.

### Event-Driven Architecture (EDA) & Event Sourcing
- **Component**: Apache Kafka backplane (`trading-contracts`, `order-management-service`, `execution-service`).
- **Implementation**: Asynchronous event streams on versioned topics:
  - `emporia.order.commands.v1` (`OrderCommand`)
  - `emporia.orders.v1` (`OrderDomainEvent`)
  - `emporia.order.results.v1` (`OrderCommandResult`)
  - `emporia.execution.commands.v1` (`ExecutionCommand`)
- **Benefits**: Decouples order ingress, state machine management, smart order routing, and venue execution.

### Choreography Saga Pattern (Distributed Transactions & Compensation)
- **Component**: Multi-service event flow (`order-command-service` $\rightarrow$ `order-management-service` $\rightarrow$ `execution-service` $\rightarrow$ `portfolio-service`).
- **Implementation**:
  - **Choreography**: Each service reacts to incoming Kafka domain events, performs its local database transaction, and publishes downstream events without a central orchestrator.
  - **Compensating Actions**: If an execution venue rejects an order or if liquidity is exhausted, `execution-service` emits compensating `ExecutionCommand` messages (`REJECT` / `CANCEL`). `order-management-service` handles these by updating order state to `REJECTED`/`CANCELLED` and restoring reserved trader purchasing power.
- **Benefits**: Maintains data consistency across independent microservices and databases without distributed locks or 2PC protocols.

### Command Query Responsibility Segregation (CQRS)
- **Component**: `order-command-service` (Commands) vs `order-management-service` / `market-data-service` (Queries/Events).
- **Implementation**: Write operations (`CREATE`, `MODIFY`, `CANCEL`) enter through `order-command-service`, while state projections and queries are served independently by `order-management-service` and `market-data-service`.
- **Benefits**: Optimizes read/write performance independently and isolates validation logic.

### Transactional Outbox & Idempotent Consumer Pattern
- **Component**: `order-management-service` (`processed_order_command` table) and `execution-service` (`DurableEmporiaPortfolioGateway`).
- **Implementation**:
  - `processed_order_command` records processed command IDs to reject duplicate command deliveries.
  - `DurableEmporiaPortfolioGateway` writes outgoing portfolio snapshots to PostgreSQL outbox tables before HTTP dispatch.
- **Benefits**: Ensures at-least-once Kafka processing guarantees without duplicate order execution or lost portfolio receipts.

### Database-per-Service Pattern
- **Component**: Service-owned PostgreSQL persistence for `authentication`, `static-data-service`, `user-preferences-service`, `order-management-service`, `execution-service`, and `portfolio-service`.
- **Implementation**: Docker deployments use an isolated PostgreSQL instance per stateful service. Non-Docker local runs use one local PostgreSQL instance with separate Flyway-managed schemas. No cross-database or cross-schema foreign keys or SQL queries exist.
- **Benefits**: Independent persistence ownership, independent schema migrations via Flyway, zero tight coupling between services.

---

## 2. Behavioral Design Patterns

### Strategy Pattern
- **Component**: `execution-service` (`ExecutionVenueGateway` interface).
- **Implementation**: Pluggable venue execution implementations:
  - `ExchangeCoreExecutionVenueGateway`: High-performance LMAX Disruptor gateway.
  - `FixExecutionVenueGateway`: FIXT 1.1 / FIX 5.0 SP2 protocol gateway.
  - `SimulatedExecutionVenueGateway`: Deterministic delayed fill simulation.
  - `BestVenueSelector`: Smart Order Routing (SMART) strategy inspecting market depth across venues.
  - `VwapSchedule`: Scheduled time/volume-sliced VWAP strategy.
- **Benefits**: Allows switching execution venue modes via runtime configuration properties (`emporia.execution.venue-mode`).

### State Pattern (Finite State Machine)
- **Component**: `order-management-service` (`OrderStateModel`).
- **Implementation**: Manages legal order status transitions:
  $$\text{PENDING} \rightarrow \text{LIVE} \rightarrow \text{PARTIALLY\_FILLED} \rightarrow \text{FILLED} / \text{CANCELLED} / \text{REJECTED}$$
- **Benefits**: Prevents invalid state transitions (e.g. modifying a filled order). Formally verified with TLA+ model checking.

### Observer / Publish-Subscribe Pattern
- **Component**: Kafka consumers (`OrderCommandConsumer`), SSE emitters (`OrderStreamService`), gRPC `StreamObserver`.
- **Implementation**: Pushes live order blotter updates to web browsers via Server-Sent Events (SSE) and streams conflated top-of-book market quotes to execution routing engines via gRPC.

---

## 3. Structural Design Patterns

### Adapter Pattern
- **Component**: `AlpacaIexMarketDataProvider`, `FixSimulatorMarketDataProvider`, `GrpcQuoteConverter`.
- **Implementation**: Converts third-party protocol formats (Alpaca WebSocket JSON, FIX protocol fields, gRPC Protobuf `ClobQuote`) into standard Emporia `Quote` and `ListingSnapshot` domain records.
- **Benefits**: Isolates core trading services from external exchange API details.

### Decorator Pattern
- **Component**: `DurableEmporiaPortfolioGateway` in `execution-service`.
- **Implementation**: Wraps `HttpEmporiaPortfolioGateway` to add durable outbox queuing and retry persistence over PostgreSQL without modifying the HTTP client.
- **Benefits**: Extends gateway resilience transparently.

### Facade Pattern
- **Component**: `StaticDataClient`, `TradingDataClient`.
- **Implementation**: Encapsulates Spring `RestClient` HTTP calls, OIDC Bearer token injection, retry policies, and error handling behind clean Java interfaces.

---

## 4. Creational Design Patterns

### Static Factory / Value Object Pattern
- **Component**: Java 21 Records (`OrderCommand.of(...)`, `WatchlistItem.from(...)`, `ListingSnapshot`).
- **Implementation**: Immutably instantiates domain entities with defensive list copying and constructor validation.

### Builder Pattern
- **Component**: Protobuf generated classes (`ClobQuote.newBuilder()`) and Micrometer metrics (`Gauge.builder(...)`).
- **Implementation**: Provides fluent construction for multi-field messages and metrics registrations.
