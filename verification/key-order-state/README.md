# KeY order-state experiment

This is a targeted feasibility experiment for using KeY and JML on the
sequential quantity and status logic in
`order-management-service/.../model/TradingOrder.java`.

It proves an extracted, dependency-free Java model rather than the production
JPA entity itself. The extraction is deliberate:

- quantities are integral lots instead of `BigDecimal`; the listing size
  increment maps production quantities to lots;
- the model retains the production accounting and status transitions;
- persistence, optimistic locking, Lombok, Spring, Kafka, price averaging, and
  concurrency are excluded.

## What is proved

The JML class invariants state that:

- total quantity is a positive Java `int`;
- `tradedLots + remainingLots == quantityLots`;
- traded and remaining quantities stay within their valid bounds;
- `LIVE`, `PARTIALLY_FILLED`, `FILLED`, and `CANCELLED` agree with the
  quantities;
- `targetStatus` equals the current status.

The four proof obligations establish:

| Proof | Result | Contract |
| --- | --- | --- |
| `constructor.key` | proved | construction establishes every invariant |
| `apply-fill.key` | proved | a valid fill preserves accounting and selects `PARTIALLY_FILLED` or `FILLED` |
| `modify.key` | proved | a valid modification stays above traded quantity and preserves status |
| `cancel.key` | proved | cancellation preserves quantities and selects `CANCELLED` |

All proof files use KeY's `arithmeticSemanticsCheckingOF` option. The proof
therefore checks that the model's Java integer calculations cannot overflow;
it does not silently treat Java integers as unbounded mathematical integers.
The fill implementation updates `remainingLots` first and derives
`tradedLots = quantityLots - remainingLots`, which makes the accounting
relationship and overflow argument explicit.

## Reproduce the experiment

The verified KeY source revision is:

```text
516330580f756a86c60dff3be65b1fc9887989ef
```

It identifies KeY `3.1.0-dev` on 2026-07-24. Build the executable jar with
Java 21 or later:

```bash
git clone https://github.com/KeYProject/key.git
cd key
git checkout 516330580f756a86c60dff3be65b1fc9887989ef
./gradlew :key.ui:shadowJar

export KEY_JAR="$PWD/key.ui/build/libs/key-3.1.0-dev-exe.jar"
export KEY_JAVA="/path/to/java-21/bin/java"
cd /path/to/emporia/verification/key-order-state
./verify.sh
```

Expected output:

```text
PASS: constructor
PASS: apply-fill
PASS: modify
PASS: cancel
All 4 KeY proof obligations passed.
```

Full KeY output is written under `target/logs/`. The verifier checks the open
goal count as well as the process exit code because this pinned development
revision can exit successfully even when automatic proof search leaves a goal
open.

For interactive inspection, launch the contract selector:

```bash
"$KEY_JAVA" -Xmx2g -jar "$KEY_JAR" OrderStateModel.key
```

## Verification boundaries

The contracts cover valid, normal transitions. They do not prove the
production entity's exception paths, `BigDecimal` and tick/lot conversions,
average-price calculation, JPA callbacks, or the cancellation-versus-fill
race.

KeY addresses sequential implementation logic in this experiment. Fray remains
responsible for JVM scheduling, TLA+ for abstract lifecycle traces, jqwik for
executable event sequences, and Testcontainers for PostgreSQL optimistic
locking.
