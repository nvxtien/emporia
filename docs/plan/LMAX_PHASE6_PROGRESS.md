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
- PostgreSQL outbox integration passes, including reclaiming an expired lease
  after simulated process loss.
- Full Maven reactor test suite passes locally with Java 21.
- `git diff --check` passes.

## Checkpoint

- Phase 6 recovery lifecycle work is complete.
- Keep CI's Mockito/JDK 21 agent configuration aligned with the local suite.
