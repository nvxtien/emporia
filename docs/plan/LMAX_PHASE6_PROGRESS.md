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
- `git diff --check` passes.

## Next

- Run the full module suite in CI or an environment with all Maven plugins
  cached; the local reactor-wide run is still affected by Mockito/JDK 21
  self-attach when the agent setup is bypassed.
