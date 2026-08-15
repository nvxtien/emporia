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

// Business rejections are tracked separately from infrastructure failures.
// Conflating them is what makes cash exhaustion invisible: once the seeded
// balance runs out the system starts rejecting instead of filling, downstream
// topics go quiet, and lag *improves* while the run is actually worthless.
const businessRejections = new Rate('emporia_business_rejection_rate');
const infraFailures = new Rate('emporia_infra_failure_rate');
const ordersAccepted = new Counter('emporia_orders_accepted');
const submitLatency = new Trend('emporia_submit_latency', true);

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
            preAllocatedVUs: Math.max(20, RATE * 2),
            maxVUs: Math.max(100, RATE * 10),
        },
    },
    thresholds: {
        // The cash-exhaustion guard. Aborts the run rather than producing a
        // baseline from a workload that stopped exercising the fill path.
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

function idempotencyKey() {
    if (DUPLICATE_RATE > 0 && usedKeys.length > 0 && Math.random() < DUPLICATE_RATE) {
        return usedKeys[Math.floor(Math.random() * usedKeys.length)];
    }
    const fresh = `k6-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    if (DUPLICATE_RATE > 0) {
        usedKeys.push(fresh);
        if (usedKeys.length > REPLAYABLE_KEYS) usedKeys.shift();
    }
    return fresh;
}

function submitOrder() {
    const listingId = LISTING_IDS[Math.floor(Math.random() * LISTING_IDS.length)];
    const token = TOKENS[Math.floor(Math.random() * TOKENS.length)];
    const side = MIX_SIDES ? (Math.random() < 0.5 ? 'BUY' : 'SELL') : 'BUY';
    return http.post(
        ORDERS_URL,
        JSON.stringify({
            listingId: listingId,
            side: side,
            type: 'LIMIT',
            quantity: QUANTITY,
            limitPrice: LIMIT_PRICE,
            destination: DESTINATION,
        }),
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
    const warmup = submitOrder();
    return { warmupStatus: warmup.status };
}

export default function () {
    const response = submitOrder();
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
    }

    check(response, {
        'order accepted': () => accepted,
    });
}

export function handleSummary(data) {
    const accepted = (data.metrics.emporia_orders_accepted || {}).values || {};
    const rejection = (data.metrics.emporia_business_rejection_rate || {}).values || {};
    const infra = (data.metrics.emporia_infra_failure_rate || {}).values || {};
    const latency = (data.metrics.emporia_submit_latency || {}).values || {};

    const summary = {
        offered_rate: RATE,
        duration: DURATION,
        orders_accepted: accepted.count || 0,
        business_rejection_rate: rejection.rate || 0,
        infra_failure_rate: infra.rate || 0,
        submit_latency_p50: latency.med || 0,
        submit_latency_p95: latency['p(95)'] || 0,
        submit_latency_p99: latency['p(99)'] || 0,
    };

    const out = {
        stdout:
            `\n    offered ${summary.offered_rate}/s over ${summary.duration}\n` +
            `    accepted ${summary.orders_accepted}\n` +
            `    business rejections ${(summary.business_rejection_rate * 100).toFixed(2)}%\n` +
            `    infra failures ${(summary.infra_failure_rate * 100).toFixed(2)}%\n` +
            `    submit latency p50/p95/p99 ` +
            `${summary.submit_latency_p50.toFixed(0)}/` +
            `${summary.submit_latency_p95.toFixed(0)}/` +
            `${summary.submit_latency_p99.toFixed(0)} ms\n`,
    };
    if (__ENV.SUMMARY_OUT) {
        out[__ENV.SUMMARY_OUT] = JSON.stringify(summary, null, 2);
    }
    return out;
}
