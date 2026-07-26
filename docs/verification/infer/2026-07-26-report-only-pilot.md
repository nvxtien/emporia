# Infer report-only pilot — 2026-07-26

## Outcome

The pilot completed successfully with Infer 1.3.0. It was report-only:
no production source, Maven configuration, PMD configuration, or CI gate was
changed.

| Item | Result |
|---|---|
| Scope | `market-data-service`, `execution-service`, and required reactor modules |
| Java source level | 21 |
| Captured source files | 465 |
| Reported issues | 1 |
| Post-fix reported issues | 0 |
| Build/capture status | Passed |
| Gate status | Non-gating |

The capture included generated FIX and protobuf Java sources. A future run
should exclude those directories so the analyzed file count and progress output
represent application-owned code.

## Finding

Infer reported `NULLPTR_DEREFERENCE` at
`market-data-service/src/main/java/com/emporia/marketdata/FixSimulatorMarketDataProvider.java:95`:

```java
return listings.stream()
        .map(listing -> books.get(listing.id()).snapshot())
        .toList();
```

Infer models `Map.get` as nullable and therefore considers `snapshot()` a
possible dereference of `null`.

### Triage

Classification: **not currently reachable; useful robustness opportunity**.

Immediately before the reported expression, the method iterates over the same
`listings` collection and calls `books.computeIfAbsent(...)` for each listing.
The `books` map is a `ConcurrentHashMap`, which does not store null values, and
the provider contains no `books.remove(...)` or `books.clear()` operation.
Consequently, if the first loop completes, every ID used by the second traversal
has a non-null `BookState` under the current implementation.

Infer did not preserve that relationship across the imperative loop and later
stream traversal. No suppression or production change was made during this
pilot.

If the code is hardened later, the cleanest change is to retain the
`BookState` values returned by `computeIfAbsent` in a local list and create the
snapshots from that list. That removes the second map lookup and makes the
non-null relationship explicit to both readers and static analyzers.

## Follow-up remediation

The suggested hardening was subsequently applied. `quotes(...)` now retains
each non-null `BookState` returned by `computeIfAbsent(...)` in request order
and creates snapshots directly from those objects. This removes the redundant
nullable `Map.get(...)` operation without adding a suppression or changing
quote ordering.

Validation after the change:

- The focused `market-data-service` Maven test suite passed.
- Both market-data Cucumber scenarios passed (13 steps).
- A fresh Infer capture analyzed the same 465 files and reported **no issues**.

## Reproduction

Run from the `emporia` directory:

```bash
/path/to/infer run \
  --java-version 21 \
  -o /tmp/emporia-infer-out \
  -- mvn -pl market-data-service,execution-service \
  -am -DskipTests clean compile
```

Generate SARIF from the captured results:

```bash
/path/to/infer report \
  --sarif \
  -o /tmp/emporia-infer-out
```

The pilot produced `report.txt`, `report.json`, and `report.sarif`.

## Integration observations

- Infer's Maven integration expects execution from the reactor directory; it
  failed to locate the POM when Maven was invoked from the repository root with
  `-f emporia/pom.xml`.
- Infer defaults Maven capture to Java 11. Emporia requires the explicit
  `--java-version 21` option because its contracts use records.
- The initial CI experiment should remain report-only and exclude generated
  sources. A required gate should be considered only after several differential
  runs establish a stable false-positive baseline.
