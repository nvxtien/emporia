# Local CI/CD

Emporia has no GitHub Actions workflow. CI/CD here is fully local: a git hook
runs the build/test pipeline on your machine before code leaves it, and
deployment is a script you run on demand. This was a deliberate choice, not
an oversight — see [Guidelines](#guidelines) for why that matters for how you
use it.

## What's here

| File | Purpose |
|---|---|
| [`scripts/local-ci.sh`](../scripts/local-ci.sh) | The CI pipeline: backend build+test+PMD, then frontend lint+build. |
| [`scripts/local-deploy.sh`](../scripts/local-deploy.sh) | The CD step: rebuilds Docker images and restarts the full local stack. Run manually. |
| [`.githooks/pre-push`](../.githooks/pre-push) | Calls `local-ci.sh` and blocks the push if it fails. |
| [`scripts/install-git-hooks.sh`](../scripts/install-git-hooks.sh) | One-time setup that points git at `.githooks/`. |

## Setup

Run once per clone:

```bash
scripts/install-git-hooks.sh
```

This runs `git config core.hooksPath .githooks`, which is a plain git
feature — no husky, no extra dependency. It points git at the repo's own
tracked hooks directory instead of the untracked, per-clone `.git/hooks/`.
From then on, every `git push` runs `scripts/local-ci.sh` first.

## What `local-ci.sh` checks

In order, stopping at the first failure:

1. **`exchange-core` is installed locally.** This is a Maven dependency not
   published to any repository — the [README's prerequisite
   step](../README.md#local-prerequisites) (`git clone` + `mvn install` it)
   has to have been done at least once. The script checks for it and prints
   the clone/install command if it's missing, rather than letting the real
   failure surface as an unrelated, confusing Maven error deep in the build.
2. **`mvn -f pom.xml clean verify`** — compiles, runs the full test suite,
   and runs PMD (`pmd.failOnViolation=true`, so a PMD violation fails the
   build) across all 9 backend modules.
3. **Frontend**: `npm ci`, `npm run lint` (oxlint), `npm run build` (`tsc -b
   && vite build` — this is also the typecheck).

It always builds `clean`, never incrementally — a CI gate that passes on a
stale `target/` and fails on a fresh clone isn't trustworthy. See [Resolved
issue](#resolved-issue-market-data-service-flake) for the one place this
cost something, and how it was actually fixed rather than just mitigated.

## What `local-deploy.sh` does

```bash
scripts/local-deploy.sh
```

Runs `docker compose -f docker-compose.full.yml up -d --build` — rebuilds
every service's image and (re)starts the full local stack, then prints
`docker compose ps`.

**This is not triggered automatically by a push.** Auto-restarting local
containers as a side effect of `git push` would be a surprising thing to
happen to whatever else is running on your machine at the time (another
service you're debugging, a database with in-progress test data, etc.).
Deploying locally is something you choose to do, not something that should
ambush you.

## Resolved issue: market-data-service flake

`market-data-service` used to intermittently (roughly 1-in-4 to 1-in-9 clean
builds) fail with a `NoSuchMethodError` on a protobuf-generated `Builder`'s
synthetic `access$N()` accessor — the signature of a compiled inner class
out of sync with its outer class, despite every build starting from `mvn
clean`. `local-ci.sh` briefly carried a retry-once mitigation for this;
that's been removed now that the underlying cause is fixed.

What was established during investigation:

- It predated and was unrelated to the FIX-simulator proto vendoring
  (`c790ed1`) — reproduced against the pre-vendoring pom (external
  `../../protobuf/fix` path) too, and it hit `ClobQuote`, which comes from
  the *other*, untouched proto execution, just as often as it hit anything
  FIX-related.
- The module had two separate executions of `org.xolstice:protobuf-maven-plugin`
  (`emporia-market-data-contract` and `fix-simulator-contract`) bound to the
  same module's `generate-sources` phase, both processing a proto file
  declaring `package marketdataservice;` (different `java_package`
  Java-side, so no Java class collision, but the same proto-level
  namespace) — a plausible site for shared/stale descriptor-pool state
  across the two executions within one Maven JVM compiling one set of
  sources together.
- A same-JVM phase-ordering fix (running the second execution in a later
  lifecycle phase instead of immediately after the first) did **not**
  resolve it, ruling out simple timing/ordering as the cause.
- The plugin itself (`xolstice/protobuf-maven-plugin`) is **archived by its
  maintainers as of April 2025** — no upstream fix, and the maintainers'
  own response to multi-execution complaints is "migrate to a different
  plugin."

**The actual fix**: isolate the FIX-simulator proto compile into its own
Maven module, [`fix-simulator-contracts`](../fix-simulator-contracts),
following the same pattern already established by
[`trading-contracts`](../trading-contracts) — a small module whose only job
is generating and packaging contract classes, consumed by other modules as
an ordinary jar dependency. `market-data-service` now has exactly one
protobuf-maven-plugin execution again; the two executions never share a
compile unit. This is a real fix, not a workaround: it removes the
structural precondition (two executions, one module) rather than papering
over the symptom, and it does not touch the vendored `.proto` files'
semantic content at all, so the real external FIX-simulator gRPC service
interop is untouched. Verified with 10 consecutive clean full-module
rebuilds and a full 10-module reactor `mvn clean verify`, all green.

## Guidelines

- **Don't bypass the hook to route around a real failure.** `git push
  --no-verify` exists for genuine emergencies, not for "the pipeline is
  slow today." If `local-ci.sh` fails, something is actually broken — fix
  it or ask, don't push around it.
- **If you add a new backend module or a new frontend check, wire it into
  `local-ci.sh`.** The pipeline is only as trustworthy as its coverage; a
  CI script that silently stops checking something is worse than no CI
  script, because it looks like a safety net that isn't one. New Maven
  modules are picked up automatically by `mvn verify`; nothing to add there
  unless you're introducing a new *kind* of check.
- **Don't reach for a retry to paper over flakiness.** The
  market-data-service flake documented below briefly had one; it was
  removed once the actual cause got fixed. If you hit a flaky failure,
  investigate and document it with the same rigor before deciding a retry
  is the right call, rather than the first one — a pipeline that reflexively
  retries past failures stops meaning anything.
- **`local-deploy.sh` is opt-in, not part of the push path.** Don't wire it
  into the hook. If you want push-triggered deployment later, that's a
  deliberate design decision (what triggers it, what environment, what
  happens to in-flight work) — not something to bolt on quietly.
- **This is local-only by design**, per an explicit choice over GitHub
  Actions (cloud-hosted or self-hosted). If that changes later — e.g. the
  team grows and needs a shared, always-on gate independent of anyone's
  laptop — that's a real re-evaluation, not an incremental change to these
  scripts.
