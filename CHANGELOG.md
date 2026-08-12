# Changelog

All notable changes to the **Emporia Trading Platform** project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.2.0] - 2026-08-07

### 🚀 Added
- **Ultra-Low Latency Aeron SBE & Dedicated Spin Loop Intake Engine**:
  - Integrated zero-copy Aeron IPC/UDP binary SBE intake (`AeronOrderCommandSubscriber`) streaming order commands directly into LMAX Disruptor 64K RingBuffer.
  - Dedicated single-threaded `oms-hotpath-1` consumer thread bound to `Thread.MAX_PRIORITY` running Agrona `BusySpinIdleStrategy` with CPU intrinsic `Thread.onSpinWait()`.
  - Deterministic load shedding via `ringBuffer.tryNext()` rejecting excess traffic instantly (HTTP 429) under extreme bursts.
- **100% GC-Free Hot Path Execution (0 Bytes Heap Allocation / Order)**:
  - Replaced all `BigDecimal` arithmetic with primitive 64-bit fixed-point long operations (`FixedPointMath`, scale factor 1,000,000L).
  - Replaced heap `UUID` instances with primitive 128-bit `LongPair` identifiers (most/least significant bits).
  - Implemented zero-copy Agrona `DirectBuffer` SBE flyweight views (`SbeView`) bypassing String and BigDecimal instantiations.
  - Pre-allocated 64,536 `OrderRingEvent` slots on Disruptor RingBuffer with reset lifecycle handlers.
- **ORM & Spring IoC Bypass (Raw JDBC Batching & Memory-Mapped SBE WAL)**:
  - Bypassed Hibernate ORM session tracking and Spring AOP transaction reflection proxies on the order intake hot path.
  - Asynchronous out-of-band DB persistence via `AsyncDbWriter` using raw JDBC batching (`jdbcTemplate.batchUpdate`) wrapped in `TransactionTemplate`.
  - Memory-Mapped SBE Write-Ahead Log (`MemoryMappedWalLogger`) providing 0% data loss crash recovery verified via `WalCrashRecoveryIntegrationTest` (kill -9 hard process shutdown resilience).
- **API Gateway Tier-Based Throttling & Resilience4j Circuit Breaker**:
  - Implemented Account Tier-based token bucket rate limiting (`OrderRateLimiterGatewayFilterFactory`): **5,000 req/s** for `institutional` tier, **100 req/s** for `retail` tier, with automatic whitelist for internal service accounts (`ROLE_INTERNAL_GATEWAY`).
  - Integrated Resilience4j Dynamic Circuit Breaker with HTTP 503 fallback controller (`OrdersFallbackController`) for self-healing downstream protection.
- **Database Account Tier Persistence & Admin Management APIs**:
  - Flyway migration script `V5__add_account_tier.sql` adding `tier VARCHAR(50) NOT NULL DEFAULT 'RETAIL'` column and `idx_user_account_tier` index to PostgreSQL (`emporia_authentication`).
  - Added `UserTier` enum (`RETAIL`, `INSTITUTIONAL`, `INTERNAL`, `VIP`) and updated `UserAccount` entity.
  - Propagated `.claim("tier", account.getTier().name().toLowerCase())` in OAuth2 Access Tokens (`SecurityConfig.java`).
  - Exposed Admin management API `PUT /admin/users/{userId}/tier` in `AdminUserController` with `USER_TIER_UPDATED` audit events.

---

## [0.1.0] - 2026-07-30

### 🚀 Added
- **Project-Wide JaCoCo Test Coverage (91.95%)**:
  - Expanded unit and integration test coverage across all 8 modules to achieve 91.95% overall instruction coverage (316 passing unit/integration tests).
  - `trading-contracts`: Achieved **100.00%** coverage with `TradingEventsTest`.
  - `portfolio-service`: Achieved **98.35%** coverage with receipt service, validator, and repository specs.
  - `order-command-service`: Achieved **98.51%** coverage across controllers, gateways, and token providers.
  - `static-data-service`: Achieved **96.91%** coverage with Alpaca and Legacy data importer boundary specs.
  - `order-management-service`: Achieved **94.73%** coverage with 11 new test suites covering domain command handlers, SSE streaming, recovery controllers, and metrics.
  - `user-preferences-service`: Achieved **91.75%** coverage with watchlist venue preference specs.
  - `market-data-service`: Achieved **88.75%** business logic coverage across REST, gRPC, and FIX simulator providers.
  - `execution-service`: Achieved **86.72%** coverage across ExchangeCore, FIX gateways, and strategy runtimes.
- **Architecture & Order Lifecycle Documentation**:
  - Added [`docs/ARCHITECTURE_FLOW.md`](docs/ARCHITECTURE_FLOW.md) featuring an end-to-end Mermaid architecture diagram, service ownership port matrix, and step-by-step order lifecycle guide.
- **Design Patterns Specification**:
  - Added [`docs/DESIGN_PATTERNS.md`](docs/DESIGN_PATTERNS.md) documenting API Gateway, Event-Driven Architecture, CQRS, Database-per-Service, Choreography Saga, Strategy, State Machine, Observer, Adapter, Decorator, and Facade patterns.

### 🛠️ Fixed & Improved
- **Protobuf Generation & Build Stability**:
  - Isolated `protobuf-maven-plugin` temp directories to fix build issues on macOS.
- **Fluent RestClient Code Formatting**:
  - Formatted Spring `RestClient` method calls to keep chained dot operators (`.get()`, `.uri()`, `.header()`, `.retrieve()`, `.body()`) on separate lines across `order-command-service`, `user-preferences-service`, and `market-data-service`.
- **Security Test Configurer Lambda Mocks**:
  - Standardized `SecurityConfigTest` mocks using `.thenAnswer()` to execute `Customizer` lambdas under Mockito deep stubs.

---

## [0.1.0-SNAPSHOT] - 2026-07-26

### 🚀 Added
- **Multi-Service Microservice Boundaries**:
  - Deployed independent business microservices: `authorisation-service` (port `9000`), `static-data-service` (`8081`), `user-preferences-service` (`8083`), `market-data-service` (`8084`), `order-command-service` (`8085`), `order-management-service` (`8086`), `execution-service` (`8087`), and `portfolio-service` (`8088`).
- **Kafka Event Backplane**:
  - Configured versioned Kafka topics for commands (`emporia.order.commands.v1`), domain order events (`emporia.orders.v1`), order results (`emporia.order.results.v1`), and execution commands (`emporia.execution.commands.v1`).
- **Exchange Core LMAX & FIX Protocol Adapters**:
  - Integrated `ExchangeCoreExecutionVenueGateway` with LMAX Disruptor memory engine and checkpoint stores.
  - Integrated `FixExecutionVenueGateway` supporting FIXT 1.1 / FIX 5.0 SP2 execution report processing.
- **Formal Verification & Property Invariants**:
  - Added TLA+ model checking specification for order state lifecycle (`verification/order-lifecycle/`).
  - Added Jqwik property-based tests for order quantity and price accounting.
