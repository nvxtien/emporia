// REWORK_NOTE Phase 1_2 baseline: submits orders at a controlled rate so Kafka
// consumer lag can be measured under known load.
//
// Run through scripts/perf/run-baseline.sh rather than directly — the wrapper
// mints the token, enforces the disk budget, and captures Prometheus snapshots.
//
// Direct use:
//   EMPORIA_TOKEN=... k6 run -e RATE=20 -e DURATION=60s scripts/perf/order-load.js
//
// Runs longer than the token lifetime are refused in setup(); see the note
// there. For a soak, raise OAUTH_ACCESS_TOKEN_TTL on the authentication service
// and mint afterwards.
//
// ---------------------------------------------------------------------------
// Cancellation (HOT_PATH_JPA_PLAN Phase 0)
// ---------------------------------------------------------------------------
// This script submitted orders and nothing else, so the entire cancel path -
// including the live-order store's two indexes, which exist for exactly that
// path - had never run under concurrent load. CANCEL_SHARE and CHILD_SHARE
// exist to fix that, and both default to 0 so every existing run is unchanged.
//
// Cancels are *added to* the submit stream, not substituted into it: an
// iteration always submits, and then cancels with probability CANCEL_SHARE. So
// RATE stays the submit rate and cancel load is a second, independent axis,
// which is the only way to ask whether concurrent cancels move submit latency -
// the question this exists to answer.
//
//   CANCEL_SHARE=0.5   half of the iterations also cancel an earlier order
//   CHILD_SHARE=0.3    3 in 10 submits attach to an open order as its child,
//                      so cancels walk a real tree instead of a flat list
//   OPEN_ORDERS=200    per-VU tracking depth; also how old an order is when it
//                      gets cancelled, since the oldest is cancelled first
//   CANCEL_ALL_EVERY=0 every Nth iteration of a VU issues POST /cancel-all
//
// The knobs are threaded through scripts/perf/order-path-capacity.sh, which
// already mints tokens and promotes the benchmark user out of the retail tier,
// so the cancel-heavy profile is one command:
//
//   CANCEL_SHARE=0.5 CHILD_SHARE=0.3 scripts/perf/order-path-capacity.sh
//
// Both cancel endpoints are POST on /api/orders/**, so the gateway's order
// rate limiter counts them against the same per-identity bucket as submits.
// The ceiling applies to RATE * (1 + CANCEL_SHARE), not to RATE - see
// CONFIGURATION.md, "A load test through the gateway cannot exceed 100
// orders/sec per retail user".
import http from 'k6/http';
import { check } from 'k6';
import encoding from 'k6/encoding';
import { Counter, Rate, Trend } from 'k6/metrics';

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8082';
// Full URL to POST orders to. Defaults to the gateway, which is the path a
// benchmark should measure: it exercises authentication and the whole edge.
// Overriding it to hit a service directly isolates one layer for diagnosis,
// but the result is no longer an end-to-end number - use it deliberately.
const ORDERS_URL = __ENV.ORDERS_URL || `${GATEWAY_URL}/api/orders`;
const TOKEN = __ENV.EMPORIA_TOKEN || '';
const TOKENS = __ENV.EMPORIA_TOKENS
    ? __ENV.EMPORIA_TOKENS.split(',').map((t) => t.trim()).filter((t) => t.length > 0)
    : [TOKEN];
const MIX_SIDES = __ENV.MIX_SIDES === 'true';
// Fraction of requests that replay an Idempotency-Key this VU has already used,
// rather than minting a fresh one. Zero by default, so an ordinary benchmark is
// unchanged.
//
// A soak of the deduplication index that never sends a duplicate proves almost
// nothing: the counters sit at zero because there is nothing to catch. Setting
// this makes the run continuously exercise the path the index exists for, and
// emporia.oms.dedup.duplicate_reached_db staying at zero then means something.
//
// A replay is answered from the recorded result, so it comes back 201 carrying
// the original order and counts as accepted - which is the point. A replay that
// created a second order would show up as a duplicate reaching the database.
const DUPLICATE_RATE = parseFloat(__ENV.DUPLICATE_RATE || '0');
const RATE = parseInt(__ENV.RATE || '10', 10);
const DURATION = __ENV.DURATION || '60s';
const LISTING_IDS = (__ENV.LISTING_IDS || '1').split(',').map((s) => parseInt(s.trim(), 10));
const DESTINATION = __ENV.DESTINATION || 'DMA';
const QUANTITY = __ENV.QUANTITY || '10';
// Held constant rather than randomised. Tick-size violations are rejected as
// reason=symbol, which would look identical to a risk rejection in the metrics
// and quietly corrupt the confound check below.
const LIMIT_PRICE = __ENV.LIMIT_PRICE || '100.00';

// Probability that an iteration, after submitting, also cancels the oldest
// order it is still tracking. Oldest rather than random on purpose: with a
// fixed OPEN_ORDERS depth every order then rests for a known number of
// iterations before being cancelled, so the live set the server holds settles
// at a stable size instead of drifting over the run.
const CANCEL_SHARE = parseFloat(__ENV.CANCEL_SHARE || '0');
// Probability that a submit attaches to an order this VU already has open,
// as its parentOrderId. Parents are drawn from everything open, children
// included, so depth accumulates and requestChildCancellations recurses rather
// than always terminating one level down.
const CHILD_SHARE = parseFloat(__ENV.CHILD_SHARE || '0');
const OPEN_ORDERS = parseInt(__ENV.OPEN_ORDERS || '200', 10);
// Off by default, and not merely because it is expensive. CANCEL_ALL cancels
// every live order on the *desk*, so one VU firing it invalidates every other
// VU's bookkeeping: their tracked orders come back 409 on cancel and refuse to
// take children, and setup() refuses that combination outright. Run it as its
// own profile: CANCEL_ALL_EVERY=50 scripts/perf/order-path-capacity.sh
const CANCEL_ALL_EVERY = parseInt(__ENV.CANCEL_ALL_EVERY || '0', 10);
// Reading the created order's id out of every response costs the generator CPU
// it does not otherwise spend, so it is only done when something needs the id.
const TRACKING = CANCEL_SHARE > 0 || CHILD_SHARE > 0;

// Business rejections are tracked separately from infrastructure failures.
// Conflating them is what makes cash exhaustion invisible: once the seeded
// balance runs out the system starts rejecting instead of filling, downstream
// topics go quiet, and lag *improves* while the run is actually worthless.
const businessRejections = new Rate('emporia_business_rejection_rate');
const infraFailures = new Rate('emporia_infra_failure_rate');
const ordersAccepted = new Counter('emporia_orders_accepted');
const submitLatency = new Trend('emporia_submit_latency', true);

const cancelLatency = new Trend('emporia_cancel_latency', true);
const cancelsAccepted = new Counter('emporia_cancels_accepted');
// 404 and 409 on a cancel are races, not failures: the order filled, or a
// cancel-all on the desk got there first. They are counted, and deliberately
// carry no threshold - letting them feed the business-rejection guard would
// make an ordinary race abort the run and read as cash exhaustion.
const cancelConflicts = new Rate('emporia_cancel_conflict_rate');
const childrenCreated = new Counter('emporia_children_created');
const cancelAllCancelled = new Counter('emporia_cancel_all_cancelled');

export const options = {
    // k6 computes only p(90) and p(95) by default, so p(99) would silently read
    // back as 0 rather than as an error.
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    scenarios: {
        orders: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            // Generous headroom: the arrival-rate executor must not throttle
            // itself when the system slows, or the offered rate silently
            // becomes the achieved rate and the knee can never be observed.
            //
            // A cancelling iteration issues two sequential requests, so it
            // occupies its VU for roughly twice as long and needs proportionally
            // more of them to hold the same arrival rate.
            preAllocatedVUs: Math.max(20, Math.ceil(RATE * 2 * (1 + CANCEL_SHARE))),
            maxVUs: Math.max(100, Math.ceil(RATE * 10 * (1 + CANCEL_SHARE))),
        },
    },
    thresholds: {
        // The cash-exhaustion guard. Aborts the run rather than producing a
        // baseline from a workload that stopped exercising the fill path.
        //
        // Fed by submits only. Cancels have their own classification below,
        // for the reason given on cancelConflicts.
        emporia_business_rejection_rate: [
            { threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '15s' },
        ],
        emporia_infra_failure_rate: [
            { threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '15s' },
        ],
    },
};

// Keys this VU has used, kept small: a replay is only interesting while the
// tiers that might answer it still could, and an unbounded list would grow for
// the length of a soak.
const usedKeys = [];
const REPLAYABLE_KEYS = 50;

function freshKey() {
    return `k6-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function idempotencyKey() {
    if (DUPLICATE_RATE > 0 && usedKeys.length > 0 && Math.random() < DUPLICATE_RATE) {
        return usedKeys[Math.floor(Math.random() * usedKeys.length)];
    }
    const fresh = freshKey();
    if (DUPLICATE_RATE > 0) {
        usedKeys.push(fresh);
        if (usedKeys.length > REPLAYABLE_KEYS) usedKeys.shift();
    }
    return fresh;
}

// --- what this VU still believes is open ----------------------------------
//
// Per-VU by construction: k6 gives each VU its own module instance and offers
// no shared mutable state, so a VU can only ever cancel what it created itself.
// That is also what keeps the token right - see submitOrder.

const openOrders = [];   // { id, token }, oldest first
const childIds = {};     // parent id -> [child id]
let iterations = 0;

function trackCreated(id, token, parent) {
    // A replayed Idempotency-Key is answered from the recorded result, so it
    // comes back 201 carrying the *original* order. Tracking that id twice
    // would have this VU cancel the same order twice and count the second 409
    // as a race, which is the generator confusing itself. Linear over
    // OPEN_ORDERS, which is small and costs nothing next to an HTTP round trip.
    for (let i = 0; i < openOrders.length; i++) {
        if (openOrders[i].id === id) return;
    }
    openOrders.push({ id: id, token: token });
    if (parent) {
        if (!childIds[parent.id]) childIds[parent.id] = [];
        childIds[parent.id].push(id);
    }
    // Bounded, and the bound is a modelling choice rather than housekeeping: a
    // forgotten order stays live on the server. The live set is meant to grow
    // when CANCEL_SHARE < 1, and it is the server's ability to hold it that is
    // under test.
    while (openOrders.length > OPEN_ORDERS) {
        delete childIds[openOrders.shift().id];
    }
}

// Everything one cancel takes with it. The handler walks the tree itself
// (requestChildCancellations recurses before touching the parent), so a child
// cancelled alongside its parent would answer 409 if asked again - noise that
// would sit in the same counter as a genuine race.
function forgetSubtree(rootId) {
    const gone = {};
    const stack = [rootId];
    while (stack.length > 0) {
        const id = stack.pop();
        if (gone[id]) continue;
        gone[id] = true;
        const children = childIds[id];
        if (children) {
            for (let i = 0; i < children.length; i++) stack.push(children[i]);
            delete childIds[id];
        }
    }
    for (let i = openOrders.length - 1; i >= 0; i--) {
        if (gone[openOrders[i].id]) openOrders.splice(i, 1);
    }
}

function chooseParent() {
    if (CHILD_SHARE <= 0 || openOrders.length === 0) return null;
    if (Math.random() >= CHILD_SHARE) return null;
    return openOrders[Math.floor(Math.random() * openOrders.length)];
}

function submitOrder(parent) {
    const listingId = LISTING_IDS[Math.floor(Math.random() * LISTING_IDS.length)];
    // A child is resolved with findOnDesk(deskId, parentOrderId), so it has to
    // reach the same desk as its parent. Reusing the parent's token is what
    // guarantees that when EMPORIA_TOKENS carries more than one identity;
    // picking at random would 404 against any parent from another desk and the
    // tree would never form.
    const token = parent ? parent.token : TOKENS[Math.floor(Math.random() * TOKENS.length)];
    const side = MIX_SIDES ? (Math.random() < 0.5 ? 'BUY' : 'SELL') : 'BUY';
    const body = {
        listingId: listingId,
        side: side,
        type: 'LIMIT',
        quantity: QUANTITY,
        limitPrice: LIMIT_PRICE,
        destination: DESTINATION,
    };
    if (parent) body.parentOrderId = parent.id;
    const response = http.post(
        ORDERS_URL,
        JSON.stringify(body),
        {
            headers: {
                Authorization: `Bearer ${token}`,
                'Content-Type': 'application/json',
                // Required by the API. Each submission is a distinct intent, so
                // each gets its own key; reusing one would make every order
                // after the first a deduplicated no-op and the load test would
                // silently measure nothing.
                //
                // Deliberately not __VU/__ITER: those are undefined in setup(),
                // where the warmup order is submitted, and referencing them
                // there aborts the whole run.
                'Idempotency-Key': idempotencyKey(),
            },
            tags: { name: 'POST /api/orders' },
            timeout: '30s',
        },
    );
    return { response: response, token: token };
}

function cancelOrder(entry) {
    return http.post(`${ORDERS_URL}/${entry.id}/cancel`, null, {
        headers: {
            Authorization: `Bearer ${entry.token}`,
            'Idempotency-Key': freshKey(),
        },
        // A constant tag: the id is in the URL, and without this k6 would open
        // a separate metric series for every order ever cancelled.
        tags: { name: 'POST /api/orders/{orderId}/cancel' },
        timeout: '30s',
    });
}

function cancelAll(token) {
    return http.post(`${ORDERS_URL}/cancel-all`, null, {
        headers: {
            Authorization: `Bearer ${token}`,
            'Idempotency-Key': freshKey(),
        },
        tags: { name: 'POST /api/orders/cancel-all' },
        timeout: '30s',
    });
}

// Seconds in a k6 duration string ('90s', '13m', '1h30m'). Returns 0 when the
// string is not one of those, which disables the expiry check rather than
// guessing at it.
function durationSeconds(text) {
    const parts = String(text).match(/\d+(\.\d+)?[hms]/g);
    if (!parts) return 0;
    return parts.reduce((total, part) => {
        const value = parseFloat(part);
        const unit = part[part.length - 1];
        return total + (unit === 'h' ? value * 3600 : unit === 'm' ? value * 60 : value);
    }, 0);
}

// The `exp` claim, or 0 if the token cannot be read. Never throws: a token this
// cannot parse is not a reason to refuse to run.
function tokenExpiry(jwt) {
    const parts = String(jwt).split('.');
    if (parts.length !== 3) return 0;
    try {
        return JSON.parse(encoding.b64decode(parts[1], 'rawurl', 's')).exp || 0;
    } catch (error) {
        return 0;
    }
}

export function setup() {
    if (!TOKEN) {
        throw new Error('EMPORIA_TOKEN is required; run via scripts/perf/run-baseline.sh');
    }
    if (CANCEL_SHARE < 0 || CANCEL_SHARE > 1 || CHILD_SHARE < 0 || CHILD_SHARE > 1) {
        throw new Error('CANCEL_SHARE and CHILD_SHARE are fractions of the submit rate, 0..1');
    }
    // Both on at once means a VU can pick a parent whose cancellation is
    // already pending, and the handler answers 409 "Cannot create a child for
    // an order pending cancellation" - a submit 4xx, which the business
    // rejection guard aborts on. That abort would be reported as cash
    // exhaustion, which is the wrong diagnosis for a workload the test itself
    // constructed. Refuse the combination instead of discovering it 15s in.
    if (CANCEL_ALL_EVERY > 0 && CHILD_SHARE > 0) {
        throw new Error(
            'CANCEL_ALL_EVERY and CHILD_SHARE cannot both be set: cancel-all leaves every live ' +
            'order on the desk pending cancellation, and a child of one of those is refused 409, ' +
            'which aborts the run through the business-rejection guard. Run them as two profiles.',
        );
    }

    // A token is minted once, before the run, and never refreshed - the
    // authorization server offers only authorization-code + PKCE for this
    // client, so there is no one-request renewal to do from here. Past expiry
    // the gateway answers 4xx, which the loop below counts as business
    // rejections, so an expired token aborts the run reported as refused
    // orders. That is the wrong diagnosis for the wrong cause, and it only
    // shows up on runs long enough to matter - soak tests. Refuse up front.
    const runSeconds = durationSeconds(DURATION);
    const expiresAt = tokenExpiry(TOKEN);
    const remaining = expiresAt - Math.floor(Date.now() / 1000);
    if (expiresAt && runSeconds && remaining < runSeconds) {
        throw new Error(
            `EMPORIA_TOKEN expires in ${remaining}s but this run is ${runSeconds}s. ` +
            'Everything after expiry would be counted as a business rejection and abort ' +
            'the run. Raise the lifetime for the benchmark identity - ' +
            'OAUTH_ACCESS_TOKEN_TTL on the authentication service - rather than switching ' +
            'to a client_credentials token, which would change what the run measures.',
        );
    }
    // Absorbs the known first-request 504: the ephemeral
    // order-command-service-{uuid} results consumer races its own group join,
    // so the very first submit after a restart can time out. That is a
    // readiness race tracked separately, not a load characteristic, and
    // letting it land inside the measurement window would corrupt stage A.
    const warmup = submitOrder(null);
    return { warmupStatus: warmup.response.status };
}

export default function () {
    iterations++;

    const parent = chooseParent();
    const submitted = submitOrder(parent);
    const response = submitted.response;
    submitLatency.add(response.timings.duration);

    const accepted = response.status >= 200 && response.status < 300;
    // 4xx here means the order was understood and refused: no buying power,
    // failed validation, no trading permission. 5xx and 0 (timeout/connection
    // failure) mean the platform itself is struggling.
    const businessRejected = response.status >= 400 && response.status < 500;
    const infraFailed = response.status === 0 || response.status >= 500;

    businessRejections.add(businessRejected);
    infraFailures.add(infraFailed);
    if (accepted) {
        ordersAccepted.add(1);
        if (parent) childrenCreated.add(1);
        if (TRACKING) {
            // A 201 is returned only after the writer applied the command, so
            // the order is in the live-order store by the time this reads its
            // id. Cancelling it on the next iteration cannot race the create.
            const id = trackedId(response);
            if (id) trackCreated(id, submitted.token, parent);
        }
    }

    check(response, {
        'order accepted': () => accepted,
    });

    if (CANCEL_SHARE > 0 && openOrders.length > 0 && Math.random() < CANCEL_SHARE) {
        cancelOldest();
    }

    if (CANCEL_ALL_EVERY > 0 && iterations % CANCEL_ALL_EVERY === 0) {
        issueCancelAll(submitted.token);
    }
}

// The created order's id, or null if the response cannot be read. A body this
// cannot parse must not abort the iteration: it would turn a reporting problem
// into a load-shape problem, and the run would measure something else.
function trackedId(response) {
    try {
        const parsed = response.json();
        return parsed && parsed.id ? parsed.id : null;
    } catch (error) {
        return null;
    }
}

function cancelOldest() {
    const entry = openOrders[0];
    const response = cancelOrder(entry);
    cancelLatency.add(response.timings.duration);

    const accepted = response.status >= 200 && response.status < 300;
    // 404: the order became terminal and left the live-order store before the
    // async writer had it on disk. 409: it filled, or a cancel-all on the desk
    // got there first, or its own cancellation is already pending. All three
    // are races this workload creates on purpose.
    const raced = response.status === 404 || response.status === 409;
    cancelConflicts.add(raced);
    if (accepted) cancelsAccepted.add(1);
    // Anything else 4xx is the test's own setup being wrong - an expired token,
    // a missing scope, the gateway's rate limiter - and belongs in the guard
    // that aborts the run.
    businessRejections.add(response.status >= 400 && response.status < 500 && !raced);
    infraFailures.add(response.status === 0 || response.status >= 500);

    check(response, {
        'cancel answered': () => accepted || raced,
    });

    // Dropped whether or not it was accepted: after a 404 or 409 the order is
    // not cancellable, and after a 200 the server has taken its children too.
    // Retrying either would only manufacture more conflicts.
    forgetSubtree(entry.id);
}

function issueCancelAll(token) {
    const response = cancelAll(token);
    cancelLatency.add(response.timings.duration);
    const accepted = response.status >= 200 && response.status < 300;
    if (accepted) {
        try {
            cancelAllCancelled.add(response.json().cancelled || 0);
        } catch (error) {
            // The count is diagnostic; failing to read it is not a run failure.
        }
    }
    businessRejections.add(response.status >= 400 && response.status < 500);
    infraFailures.add(response.status === 0 || response.status >= 500);
    // Every live order on the desk is now pending cancellation, including ones
    // this VU created. Cancelling them individually afterwards would answer 409
    // and count as a race that the test itself caused.
    openOrders.length = 0;
    Object.keys(childIds).forEach(function (key) { delete childIds[key]; });
}

export function handleSummary(data) {
    const accepted = (data.metrics.emporia_orders_accepted || {}).values || {};
    const rejection = (data.metrics.emporia_business_rejection_rate || {}).values || {};
    const infra = (data.metrics.emporia_infra_failure_rate || {}).values || {};
    const latency = (data.metrics.emporia_submit_latency || {}).values || {};
    const cancelled = (data.metrics.emporia_cancels_accepted || {}).values || {};
    const cancelRaces = (data.metrics.emporia_cancel_conflict_rate || {}).values || {};
    const cancelTime = (data.metrics.emporia_cancel_latency || {}).values || {};
    const children = (data.metrics.emporia_children_created || {}).values || {};
    const cancelAllTotal = (data.metrics.emporia_cancel_all_cancelled || {}).values || {};

    const summary = {
        offered_rate: RATE,
        duration: DURATION,
        orders_accepted: accepted.count || 0,
        business_rejection_rate: rejection.rate || 0,
        infra_failure_rate: infra.rate || 0,
        submit_latency_p50: latency.med || 0,
        submit_latency_p95: latency['p(95)'] || 0,
        submit_latency_p99: latency['p(99)'] || 0,
        cancel_share: CANCEL_SHARE,
        child_share: CHILD_SHARE,
        cancels_accepted: cancelled.count || 0,
        cancel_conflict_rate: cancelRaces.rate || 0,
        cancel_latency_p50: cancelTime.med || 0,
        cancel_latency_p95: cancelTime['p(95)'] || 0,
        cancel_latency_p99: cancelTime['p(99)'] || 0,
        children_created: children.count || 0,
        cancel_all_cancelled: cancelAllTotal.count || 0,
    };

    let text =
        `\n    offered ${summary.offered_rate}/s over ${summary.duration}\n` +
        `    accepted ${summary.orders_accepted}\n` +
        `    business rejections ${(summary.business_rejection_rate * 100).toFixed(2)}%\n` +
        `    infra failures ${(summary.infra_failure_rate * 100).toFixed(2)}%\n` +
        `    submit latency p50/p95/p99 ` +
        `${summary.submit_latency_p50.toFixed(0)}/` +
        `${summary.submit_latency_p95.toFixed(0)}/` +
        `${summary.submit_latency_p99.toFixed(0)} ms\n`;
    // Only when cancels ran, so a submit-only run prints exactly what it always
    // printed and the wrappers that tail this output are unaffected.
    if (CANCEL_SHARE > 0 || CANCEL_ALL_EVERY > 0) {
        text +=
            `    cancels accepted ${summary.cancels_accepted}` +
            ` (share ${CANCEL_SHARE}, children ${summary.children_created}` +
            `, cancel-all took ${summary.cancel_all_cancelled})\n` +
            `    cancel races (404/409) ${(summary.cancel_conflict_rate * 100).toFixed(2)}%\n` +
            `    cancel latency p50/p95/p99 ` +
            `${summary.cancel_latency_p50.toFixed(0)}/` +
            `${summary.cancel_latency_p95.toFixed(0)}/` +
            `${summary.cancel_latency_p99.toFixed(0)} ms\n`;
    }

    const out = { stdout: text };
    if (__ENV.SUMMARY_OUT) {
        out[__ENV.SUMMARY_OUT] = JSON.stringify(summary, null, 2);
    }
    return out;
}
