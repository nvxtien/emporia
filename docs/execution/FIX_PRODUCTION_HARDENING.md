# FIX execution gateway: production hardening design

Status: **A, B, C, and D are implemented and tested** (see below); **only E
(counterparty certification) remains, and it isn't code.** Written in response to the boundary
documented in [`docs/execution/README.md`](README.md#current-boundaries):

> The built-in FIX adapter does not provide durable sequence storage, resend
> recovery, certificates, counterparty certification, or production session
> controls.

This document breaks that gap into independently sized pieces, grounded in the
current implementation at
[`FixExecutionVenueGateway.java`](../../execution-service/src/main/java/com/emporia/execution/FixExecutionVenueGateway.java)
(371 lines, one `FixSession` per configured venue MIC).

## Current state

- `FixSession.outgoingSequence` is an in-memory `AtomicInteger`, reset to `1`
  on every reconnect. Nothing tracks the incoming sequence number.
- `onMessage`/`handle` never inspects `MsgSeqNum` (34). A missed or
  out-of-order message is simply processed as if it arrived in order.
- `ResendRequest` (35=2) and `SequenceReset`/gap-fill (35=4) are not handled at
  all — falls through to `sink.accept`, which looks up the order by ClOrdID
  and (for a session-level message with no ClOrdID) logs "unknown ClOrdID"
  noise.
- `Logout` (35=5) just sets `running = false`; there is no Logout reply sent
  either direction — the socket is torn down, not gracefully closed per the
  FIX session protocol.
- No `TestRequest` (35=1) watchdog: `read()` blocks on `InputStream.read()`
  with no `SO_TIMEOUT`, so a silently-dead TCP connection (no RST/FIN) hangs
  undetected until the OS eventually notices.
- `Reject` (35=3) is not special-cased — same "unknown ClOrdID" fallthrough as
  ResendRequest.
- The socket is a plain `java.net.Socket`; there is no TLS.
- `V1__create_portfolio_outbox.sql` is the only Flyway migration in
  `execution-service`'s `emporia_execution` schema — there is no FIX-specific
  persistence today.

## Proposed pieces

### A. Durable sequence number persistence — implemented

Implemented as designed below: `fix_session_state` (`V2__create_fix_session_state.sql`),
`FixSessionStateStore`/`JdbcFixSessionStateStore`, and `FixSession` seeding
`outgoingSequence`/`incomingSequence` from the store on every `connectLoop`
iteration, with `ResetSeqNumFlag(141)=Y` sent on a fresh session. Tested in
`FixExecutionVenueGatewayTest` and `JdbcFixSessionStateStoreTest` (H2, same-day
reuse, day-rollover reset, per-venue isolation). Incoming sequence numbers are
persisted but not yet gap-validated — that's still part B.


FIX requires `MsgSeqNum` to increase monotonically per session
(`SenderCompID`/`TargetCompID` pair) across reconnects within a trading day; a
reset to `1` after every disconnect will desync from any real counterparty
after the first drop.

- New table (Flyway migration in `execution-service`):

  ```sql
  create table fix_session_state (
      mic              varchar(16) primary key,
      session_date     date not null,
      outgoing_seq_num integer not null,
      incoming_seq_num integer not null,
      updated_at       timestamptz not null
  );
  ```

- `FixSession` loads its row on `start()` before sending `Logon`, and seeds
  `outgoingSequence` from the persisted value (or `1` with
  `ResetSeqNumFlag(141)=Y` on a `session_date` rollover — configurable per
  venue, since not every counterparty resets sequences the same way).
- Every sent message increments and persists `outgoing_seq_num`; every
  accepted inbound message persists `incoming_seq_num`. A synchronous
  per-message row update is acceptable at order-flow message rates; batch it
  later only if profiling says so.
- New config surface: a per-venue daily-reset time (most venues reset at a
  fixed UTC hour), e.g. extending the existing
  `FIX_EXECUTION_VENUES=MIC=host:port:senderCompId:targetCompId` format or a
  companion `FIX_SESSION_RESET_UTC_HOUR` variable.

This is the prerequisite for everything else below — resend/gap-fill and
session controls both need to know "what sequence number are we actually at."

### B. Message store + resend/gap-fill (the largest piece) — implemented

- New table:

  ```sql
  create table fix_message_log (
      mic         varchar(16) not null,
      seq_num     integer not null,
      msg_type    varchar(4) not null,
      raw_message text not null,
      sent_at     timestamptz not null,
      primary key (mic, seq_num)
  );
  ```

  Only outbound application-level messages need literal storage; outbound
  admin/session messages can be summarized with a `SequenceReset`/gap-fill on
  resend instead of replayed verbatim (standard FIX practice).

- **Outbound resend**: on receiving `ResendRequest` (35=2,
  `BeginSeqNo`(7)/`EndSeqNo`(16), where `0` means "through current"), read the
  requested range from `fix_message_log` and retransmit each application
  message with `PossDupFlag(43)=Y` and the original `OrigSendingTime(122)`;
  bridge any consecutive admin-message gaps in that range with a single
  `SequenceReset`-`GapFill` (35=4, `GapFillFlag(123)=Y`).
- **Inbound gap detection**: track expected incoming `MsgSeqNum`. If a message
  arrives higher than expected, buffer it in a small reorder map, send our own
  `ResendRequest` for the missing range, and apply buffered messages once the
  gap is filled by either literal resend or a `SequenceReset`-`GapFill`.
- Needs a retention/pruning policy (session-date scoped — same-day resend is
  the only realistic requirement; a daily cleanup job or `session_date`-keyed
  deletion on rollover is enough).

Flag this explicitly: this is the one piece that's a genuine new subsystem
(persistent outbound log + inbound reorder buffer + protocol state machine),
not a small addition to the existing `FixSession`. Everything else in this
document is comparatively contained.

Implemented as designed: `fix_message_log` (`V3__create_fix_message_log.sql`),
`FixMessageLogStore`/`JdbcFixMessageLogStore`, `FixSession.onIncomingMessage`
(gap detection + reorder buffer + `ResendRequest`), `handleResendRequest`
(replay + `GapFill` bridging), and `messageLog.clear(mic)` on a fresh session
so a new day's sequence numbers (which restart at 1) never collide with the
previous day's `(mic, seq_num)` rows. One simplification versus the original
design: the reorder buffer has no size cap or stuck-gap timeout — if a
requested resend never arrives, buffered messages accumulate in memory
indefinitely rather than eventually erroring or forcing a reconnect. Tested in
`JdbcFixMessageLogStoreTest` (persistence layer) and
`FixExecutionVenueGatewayTest` (`outOfOrderMessagesAreBufferedAndReleasedOnceTheGapIsFilled`,
`respondsToResendRequestByReplayingLoggedApplicationMessages`,
`respondsToResendRequestWithGapFillForUnloggedAdminMessages`).

### C. TLS / mutual TLS — implemented

Implemented via `SSLSocketFactory` (`FIX_TLS_ENABLED`, `FIX_TLS_TRUSTSTORE_*`,
`FIX_TLS_KEYSTORE_*`), with `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")`
enforcing hostname verification and an explicit 10s handshake timeout separate
from the TCP connect timeout. No default truststore path means "use the JVM's
public CA trust store." Tested end-to-end in `FixExecutionVenueGatewayTlsTest`
against a real `SSLServerSocket` and a self-signed test certificate
(`src/test/resources/fix-tls/`, generated with `keytool`, not a real secret).


- Swap `new Socket()` for an `SSLSocketFactory`-created socket when a per-venue
  TLS flag is set. `SSLSocket extends Socket`, so the rest of `FixSession`'s
  read/write loop is unaffected.
- Support both server-auth-only TLS (trust store with the venue's CA) and
  mutual TLS (client keystore, since many FIX order-routing networks require
  client certs). Config surface: `FIX_TLS_ENABLED`,
  `FIX_TLS_TRUSTSTORE_PATH`/`_PASSWORD`, `FIX_TLS_KEYSTORE_PATH`/`_PASSWORD`
  (or per-venue variants) — sourced from a real secrets manager in production,
  not committed config, consistent with the secrets-management gap already
  flagged for the platform generally.
- Keep hostname verification and certificate validation on; don't expose a
  toggle to disable them.
- Add an explicit TLS handshake timeout distinct from the plain-TCP connect
  timeout already in `connectLoop`.

Comparatively small: mostly configuration and a factory swap, no new protocol
state machine.

### D. Production session controls — implemented

- **Logout handshake**: on receiving an unsolicited `Logout` (35=5), send a
  `Logout` reply before closing (currently: silently drops to `running=false`
  with no reply). When we initiate logout (`stop()`), send `Logout` first and
  wait (bounded) for the counterparty's `Logout` reply before closing the
  socket, rather than closing immediately.
- **Heartbeat watchdog / `TestRequest`**: set `Socket.setSoTimeout(...)` so the
  read loop can notice silence; track last-received-message time and, once it
  exceeds `HeartBtInt` by a grace multiplier (~1.2x is a common convention),
  send `TestRequest` (35=1) with a fresh `TestReqID`(112). If no response
  arrives within another interval, treat the session as dead and reconnect.
  Today the blocking read has no timeout, so a half-open TCP connection can
  hang undetected indefinitely.
- **Session-level `Reject` (35=3)**: handle explicitly — log at error level
  with `SessionRejectReason(373)`/`Text(58)`, and treat repeated rejects as
  cause for a controlled disconnect/alert rather than continuing to send.
- **Session scheduling**: some venues only accept connections in a defined
  trading-session window. Add a configurable per-venue start/end so the
  gateway doesn't hammer reconnects outside trading hours and proactively logs
  out at session end instead of being cut off. **Not implemented** — the other
  three bullets above are done; session scheduling is still open.

Logout handshake, the heartbeat watchdog, and Reject handling are implemented
in `FixSession` (`stop()`, `read()`, `handle()`) and tested in
`FixExecutionVenueGatewayTest` (`stopSendsALogoutAndWaitsForTheAcknowledgement`,
`sessionLevelRejectDoesNotDisruptExecutionReportProcessing`). The watchdog's
multi-second thresholds aren't exercised by a fast unit test — that's a
coverage gap, not a design gap.

### E. Counterparty certification — process, not code

This is a real venue's or broker's sign-off after you run their FIX
conformance suite against your gateway in their UAT/certification
environment (new order, cancel, replace, reject, resend/gap-fill drills,
session-level reject drills). It cannot be "implemented"; it's an onboarding
workflow with the counterparty.

What's buildable in-repo to *rehearse* for it: a FIX order-routing
conformance harness. [`fix-market-simulator`](../../fix-market-simulator)
(now an emporia module in its own right, copied in from the wider
`j-trading` workspace it originally lived in) already contains an order
book/exchange simulation (`OrderBookImpl`, `ExchangeImpl`, `LimitOrderImpl`)
and a FIX protocol object model — but it's currently wired as
`market-data-service`'s book-building data source over gRPC, not as an
order-entry FIX counterparty for `execution-service`. Whether it already
speaks order-entry FIX (`NewOrderSingle`/`ExecutionReport`) as a session
counterparty, or would need that added, needs a closer look before committing
to reusing it — flagging as an open question rather than assuming.

## Rollout order

Pieces A, C, and D are each self-contained and don't block each other. B
depends on A (a resend range is meaningless without accurate sequence
tracking) and is the largest single piece of new infrastructure. E can proceed
in parallel once there's something worth rehearsing against.

Suggested order: **A → D → C → B**, then E. That gets sequence correctness and
session robustness (the things most likely to silently corrupt state today)
in place first, adds transport security next, and leaves the standalone
resend/gap-fill subsystem — the biggest lift — for last.

## Testing implications

- Unit tests for `FixSession`: sequence persistence (mock repository), gap
  detection, Logout handshake state transitions, TestRequest watchdog timing.
- Extend whatever counterparty simulator is used (see E) to drive: mid-session
  disconnects, `ResendRequest` scenarios, deliberate sequence gaps,
  heartbeat-timeout scenarios, and a TLS handshake with a self-signed test
  certificate.
- Integration test: restart `execution-service` mid FIX session and confirm
  sequence continuity against the counterparty simulator, matching the pattern
  already used for exchange-core checkpoint recovery
  (`ExchangeCoreExecutionVenueGateway`'s `ExchangeCoreCheckpointStore`).
