// REWORK_NOTE Phase 1_2 baseline: submits orders at a controlled rate so Kafka
// consumer lag can be measured under known load.
//
// Run through scripts/perf/run-baseline.sh rather than directly — the wrapper
// mints the token, enforces the disk budget, and captures Prometheus snapshots.
//
// Direct use:
//   EMPORIA_TOKEN=... k6 run -e RATE=20 -e DURATION=60s scripts/perf/order-load.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8082';
const TOKEN = __ENV.EMPORIA_TOKEN || '';
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

function submitOrder() {
    const listingId = LISTING_IDS[Math.floor(Math.random() * LISTING_IDS.length)];
    return http.post(
        `${GATEWAY_URL}/api/orders`,
        JSON.stringify({
            listingId: listingId,
            side: 'BUY',
            type: 'LIMIT',
            quantity: QUANTITY,
            limitPrice: LIMIT_PRICE,
            destination: DESTINATION,
        }),
        {
            headers: {
                Authorization: `Bearer ${TOKEN}`,
                'Content-Type': 'application/json',
            },
            tags: { name: 'POST /api/orders' },
            timeout: '30s',
        },
    );
}

export function setup() {
    if (!TOKEN) {
        throw new Error('EMPORIA_TOKEN is required; run via scripts/perf/run-baseline.sh');
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
