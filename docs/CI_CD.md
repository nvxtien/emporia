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
stale `target/` and fails on a fresh clone isn't trustworthy. See [Known
issue](#known-issue-market-data-service-flake) for the one place this cost
something.

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

## Known issue: market-data-service flake

`local-ci.sh`'s backend step retries once on failure. This isn't a generic
"tests are flaky, just retry" policy — it's a specific, investigated,
documented mitigation for one known issue:

`market-data-service` intermittently (roughly 1-in-4 to 1-in-9 clean builds)
fails with a `NoSuchMethodError` on a protobuf-generated `Builder`'s
synthetic `access$N()` accessor — the signature of a compiled inner class
out of sync with its outer class, despite every build starting from `mvn
clean`. What's been established so far:

- It predates and is unrelated to the FIX-simulator proto vendoring
  (`c790ed1`) — confirmed by reproducing it against the pre-vendoring pom
  (external `../../protobuf/fix` path) and by it hitting `ClobQuote`, which
  comes from the **other**, untouched proto execution.
- The module has two separate executions of `org.xolstice:protobuf-maven-plugin`
  (`emporia-market-data-contract` and `fix-simulator-contract`), both bound
  to `generate-sources`, both processing a proto file declaring `package
  marketdataservice;` (different `java_package` Java-side, so no Java class
  collision, but the same proto-level namespace) — a plausible site for
  shared/stale descriptor-pool state across the two executions within one
  Maven JVM.
- A same-JVM phase-ordering fix (running the second execution in a later
  lifecycle phase instead of immediately after the first) **did not**
  resolve it.
- The plugin itself (`xolstice/protobuf-maven-plugin`) was **archived by its
  maintainers in April 2025** — no upstream fix is coming, and the
  maintainers' own response to multi-execution complaints is "migrate to a
  different plugin."
- The vendored `.proto` files were deliberately **not** modified to test
  further theories (e.g. renaming the shared package) — they're a wire
  contract with a real external FIX-simulator gRPC service, and an
  experiment that silently breaks interop with that service is worse than
  the flake it would be testing.

**If you want to actually fix this** rather than live with the retry, the
two real options are: isolate the `fix-simulator-contract` proto compile
into its own Maven module (a different reactor build context, likely
avoiding whatever shared state causes this), or migrate the module off the
archived plugin. Both are real, scoped pieces of work — not something to
start as a drive-by.

## Guidelines

- **Don't bypass the hook to route around a real failure.** `git push
  --no-verify` exists for genuine emergencies, not for "the pipeline is
  slow today." If `local-ci.sh` fails and it isn't the documented
  market-data-service flake, something is actually broken — fix it or ask,
  don't push around it.
- **If you add a new backend module or a new frontend check, wire it into
  `local-ci.sh`.** The pipeline is only as trustworthy as its coverage; a
  CI script that silently stops checking something is worse than no CI
  script, because it looks like a safety net that isn't one.
- **Don't add more retries to paper over new flakiness.** The one retry in
  `local-ci.sh` is scoped to a specific, investigated, documented issue. If
  you hit a *different* flaky failure, investigate and document it the same
  way before deciding a retry is the right mitigation — reflexively
  retrying makes the pipeline gradually meaningless.
- **`local-deploy.sh` is opt-in, not part of the push path.** Don't wire it
  into the hook. If you want push-triggered deployment later, that's a
  deliberate design decision (what triggers it, what environment, what
  happens to in-flight work) — not something to bolt on quietly.
- **This is local-only by design**, per an explicit choice over GitHub
  Actions (cloud-hosted or self-hosted). If that changes later — e.g. the
  team grows and needs a shared, always-on gate independent of anyone's
  laptop — that's a real re-evaluation, not an incremental change to these
  scripts.
