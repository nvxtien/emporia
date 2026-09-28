# LMAX Phase 6 Progress

## Recovery lifecycle

- `LiveOrderStoreWarmup` loads synchronously in a phased `SmartLifecycle` before
  the OMS ring starts.
- `DedupIndexWarmup` loads and publishes durable history synchronously after the
  live-order store and before the OMS ring.
- Aeron order intake starts in a later lifecycle phase, after the OMS ring is
  ready to receive commands.
- Failed or incomplete warm-up remains fail-closed; no partial index is
  published as ready.

## Verification

- Recovery warm-up, Aeron intake, and cross-command interleaving tests pass.
- `git diff --check` passes.

## Next

- Run the full module suite in an environment with Maven dependencies cached and
  Byte Buddy self-attach enabled.
- Keep the documented reactor-wide Mockito/JDK 21 limitation separate from OMS
  recovery changes.
