# Software Design Patterns in the Emporia Trading Platform

This document details the software design patterns used across the **Emporia Trading Platform** architecture, split by domain and design intent.

---

## 1. Architectural & Distributed Systems Patterns

### API Gateway Pattern
- **Component**: `gateway` (Spring Cloud Gateway on port `8082`).
- **Implementation**: Single reverse-proxy entry point for frontend requests (`/api/*`).
- **Benefits**: Decouples the React SPA from internal microservices, handles CORS, routes by path/method, and enforces OAuth2/OIDC PKCE token validation.

### Event-Driven Architecture (EDA)
- **Component**: In-process OMS event flow and durable PostgreSQL outboxes.
- **Implementation**: Commands enter the LMAX Disruptor ring; the single writer
  emits typed domain events to sharded execution dispatch and persists durable
  delivery records asynchronously.
- **Benefits**: Keeps the order path deterministic and low-latency while
  retaining crash recovery and at-least-once output delivery.

### Choreography Saga Pattern (Distributed Transactions & Compensation)
- **Component**: In-process OMS execution flow and the portfolio HTTP boundary.
- **Implementation**: Venue reports re-enter the OMS ring as typed commands;
  portfolio snapshots use a PostgreSQL outbox and idempotent receipts.
- **Benefits**: Maintains consistency across service-owned databases without
  distributed locks or 2PC protocols.

### Command Query Responsibility Segregation (CQRS)
- **Component**: Gateway write path into `order-management-service` (commands via Disruptor) vs query/SSE projections on the same service and `market-data-service`.
- **Implementation**: Browser mutations enter OMS in-process through the
  Disruptor; queries and blotter streams are served independently from the
  command path.
- **Benefits**: Keeps reads and streaming projections from blocking the order
  state machine.

### Transactional Outbox & Idempotent Consumer Pattern
- **Component**: `order-management-service` (`processed_order_command` and
  `order_delivery_outbox`) plus `portfolio-service` receipt handling.
- **Implementation**:
  - `processed_order_command` records processed command IDs to reject duplicate command deliveries.
  - `DurableEmporiaPortfolioGateway` writes outgoing portfolio snapshots to PostgreSQL outbox tables before HTTP dispatch.
- **Benefits**: Ensures at-least-once durable delivery without duplicate order
  execution or lost portfolio receipts.

### Database-per-Service Pattern
- **Component**: Service-owned PostgreSQL persistence for `authentication`, `static-data-service`, `user-preferences-service`, `order-management-service`, and `portfolio-service`.
- **Implementation**: Docker deployments use an isolated PostgreSQL instance per stateful service. Non-Docker local runs use one local PostgreSQL instance with separate Flyway-managed schemas. No cross-database or cross-schema foreign keys or SQL queries exist.
- **Benefits**: Independent persistence ownership, independent schema migrations via Flyway, zero tight coupling between services.

---

## 2. Behavioral Design Patterns

### Strategy Pattern
- **Component**: `order-management-service` (`ExecutionVenueGateway` interface).
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
- **Component**: SSE emitters (`OrderStreamService`) and gRPC `StreamObserver`.
- **Implementation**: Pushes live order blotter updates to web browsers via
  Server-Sent Events (SSE) and streams conflated top-of-book market quotes to
  execution routing engines via gRPC.

---

## 3. Structural Design Patterns

### Adapter Pattern
- **Component**: `AlpacaIexMarketDataProvider`, `FixSimulatorMarketDataProvider`, `GrpcQuoteConverter`.
- **Implementation**: Converts third-party protocol formats (Alpaca WebSocket JSON, FIX protocol fields, gRPC Protobuf `ClobQuote`) into standard Emporia `Quote` and `ListingSnapshot` domain records.
- **Benefits**: Isolates core trading services from external exchange API details.

### Decorator Pattern
- **Component**: `DurableEmporiaPortfolioGateway` in `order-management-service`.
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
