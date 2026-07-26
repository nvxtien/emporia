# Order lifecycle TLA+ model

This model checks one order moving through:

```text
NEW -> LIVE -> PARTIALLY_FILLED -> FILLED
  |       |             |
  v       +-------------+-------> CANCELLED
REJECTED
```

Cancellation is represented by two steps:

1. `RequestCancel` records that cancellation is pending.
2. `ConfirmCancel` moves an active order to `CANCELLED`.

An `ApplyFill` step remains possible between those steps. TLC therefore explores
the important race outcomes:

- cancellation wins and freezes the current filled quantity;
- a partial fill arrives first and cancellation preserves that fill;
- a full fill arrives first and the order remains `FILLED`;
- execution finishes before cancellation is requested.

## Checked properties

| Property | Guarantee |
|---|---|
| `TypeOK` | Status, filled quantity, and pending-cancel flag stay in their domains. |
| `FilledBounds` | Filled quantity never becomes negative or exceeds the order quantity. |
| `StatusMatchesFill` | Partial and full statuses agree with the filled quantity. |
| `CancelRequestConsistent` | A pending cancel exists only for an active or just-filled order. |
| `TerminalStatusNeverChanges` | `FILLED`, `CANCELLED`, and `REJECTED` never transition to another status. |
| `NoExecutionAfterCancellation` | Filled quantity cannot change after cancellation is confirmed. |
| `CancelRequestEventuallyResolves` | Every pending cancellation is eventually confirmed or loses to a full fill. |

The TLC configuration uses `Quantity = 3`. That finite domain is sufficient to
enumerate partial-fill and full-fill combinations while keeping the state space
small.

## Run TLC

Download `tla2tools.jar` from the official TLA+ releases, then run:

```bash
cd emporia/verification/order-lifecycle
java -XX:+UseParallelGC -jar /path/to/tla2tools.jar \
  -config OrderLifecycle.cfg \
  -metadir target/tlc \
  OrderLifecycle.tla
```

TLC should finish with `Model checking completed. No error has been found.`

Last verified with TLC 2.19:

```text
22 states generated
13 distinct states found
0 states left on queue
Depth of the complete state graph: 5
Model checking completed. No error has been found.
```

## Scope

The model intentionally abstracts away prices, users, persistence, Kafka,
optimistic versions, and command idempotency. The jqwik tests cover numeric
validation, versioned modifications, terminal cancellation, and duplicate
commands in executable Java. This specification focuses on the future execution
transition and its concurrency with cancellation.
