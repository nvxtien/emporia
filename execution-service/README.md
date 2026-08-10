# Execution service

The execution service owns DMA, SMART, VWAP, and execution-venue integration.
It is an internal Kafka consumer/producer on port `8087`; browsers do not call
it.

See the [DMA, SMART, and VWAP execution guide](../docs/execution/README.md) for
end-to-end flows, API examples, strategy behavior, cancellation, and recovery.

## Routes

The service consumes immutable order events from `emporia.orders.v1`.

- `DMA` submits, modifies, and cancels through the configured venue gateway.
- `SMART` requests all listings for the instrument and current venue quotes,
  walks executable opposite-side depth in price order, and emits deterministic
  DMA children across as many venues as required. Limit protection is applied
  to every level. If no liquidity is executable, the strategy stays live and
  retries.
- `VWAP` accepts `utcStartTimeSecs`, `utcEndTimeSecs`, and `buckets`. It
  periodically compares the cumulative schedule target with persisted parent
  fills plus active child exposure, then emits only the increment-aligned
  catch-up quantity.

Venue fills, rejects, and unsolicited cancellations are published to
`emporia.execution.commands.v1`. Order management applies those commands
transactionally, records executions, and publishes the resulting order state.
It also rolls every partial or full child fill up to all parent ancestors in
that same transaction. Execution references and deterministic child IDs make
redelivery idempotent.

A cancel request is asynchronous: order management records
`targetStatus=CANCELLED`, execution forwards the request, and the venue
acknowledgement finalizes the order. A fill that races the acknowledgement is
not discarded.

At startup, the service reads `/internal/execution/recoverable` from order
management. Direct orders are reattached or have a pending cancel resent;
SMART/VWAP runtimes resume from the persisted parent and child state. The FIX
adapter restores its order/ClOrdID maps without submitting another New Order
Single.

The execution consumer uses `auto.offset.reset=latest` deliberately. A newly
introduced execution service must not route old retained `CREATED` events.
Restart recovery comes from the PostgreSQL projection rather than replaying
historical creation events.

## Venue modes

The default `simulated` gateway fills the order’s remaining quantity after
`EXECUTION_FILL_DELAY` and cancels a pending fill when a cancellation arrives.
This mode is for local development.

Set `EXECUTION_VENUE_MODE=fix` for the built-in FIX source adapter:

```bash
EXECUTION_VENUE_MODE=fix \
FIX_EXECUTION_VENUES='XNAS=localhost:9878:EMPORIA:NASDAQ,XNYS=localhost:9879:EMPORIA:NYSE' \
mvn -f emporia/execution-service/pom.xml spring-boot:run
```

Each entry is `MIC=host:port:senderCompId:targetCompId`. The adapter uses FIXT
1.1 with FIX 5.0 SP2 application messages and supports:

- Logon, logout, heartbeat, test request, reconnect, and session sequence;
- New Order Single (`D`);
- Order Cancel/Replace (`G`);
- Order Cancel Request (`F`);
- Execution Report (`8`) fills, rejects, and venue cancellations.

This source adapter is not a production FIX engine: durable sequence storage,
resend recovery, certificates, counterparty certification, and operational
session controls are required before real venue onboarding.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8087` | Internal actuator port |
| `KAFKA_BROKERS` | `localhost:9092` | Kafka bootstrap servers |
| `EMPORIA_STATIC_DATA_URL` | `http://localhost:8081` | Listing lookup |
| `EMPORIA_MARKET_DATA_URL` | `http://localhost:8084` | Venue quote lookup |
| `EMPORIA_ORDER_MANAGEMENT_URL` | `http://localhost:8086` | Strategy state and restart recovery |
| `EXECUTION_OAUTH_CLIENT_ID` | `emporia-execution` | Service OAuth client |
| `EXECUTION_OAUTH_CLIENT_SECRET` | local development value | Service OAuth secret |
| `EXECUTION_VENUE_MODE` | `simulated` | `simulated`, `fix`, or `exchange-core` |
| `EXECUTION_ORDERS_CONSUMER_CONCURRENCY` | `6` | Kafka listener concurrency for `emporia.orders.v1`; defaults to the topic partition count |
| `EXECUTION_FILL_DELAY` | `100ms` | Simulated fill delay |
| `EXCHANGE_CORE_RETAINED_CHECKPOINTS` | `2` | Total exchange-core checkpoint ids retained after a successful manifest update when using `exchange-core` mode |
| `EXCHANGE_CORE_MIN_FREE_STORAGE_BYTES` | `0` | Optional pre-checkpoint free-space floor for exchange-core storage when using `exchange-core` mode |
| `STRATEGY_TIME_COMPRESSION` | `60` | Local VWAP time compression |
| `VWAP_DEFAULT_BUCKETS` | `10` | Default VWAP child count |

When `EXECUTION_VENUE_MODE=exchange-core` runs with the `prod` or `production`
Spring profile, startup fails fast unless `EXCHANGE_CORE_STORAGE_DIRECTORY`
points outside `.local-run` and `EXCHANGE_CORE_MIN_FREE_STORAGE_BYTES` is
greater than `0`.

Metrics are available from `/actuator/prometheus`, including routed orders,
routing rejects, published fills, and exchange-core checkpoint age, retention,
storage, and usable-storage gauges. In `exchange-core` mode, `/actuator/health`
reports `DOWN` when retained checkpoint storage status is unavailable or when
checkpoint failures have not yet been cleared by a successful snapshot.

## Verify

```bash
mvn -f emporia/pom.xml -pl execution-service -am test
```

The tests cover price/time priority, depth walking, limit protection,
no-liquidity retry, VWAP quantity conservation and cumulative targets, DMA
dispatch/cancel acknowledgement, multi-venue SMART children, and restart
recovery. Atomic partial/final parent roll-up is tested in
`order-management-service`.
