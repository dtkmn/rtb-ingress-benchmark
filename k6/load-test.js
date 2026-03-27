import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const ACCEPTED_RESPONSES = new Counter('accepted_responses');
const FILTERED_RESPONSES = new Counter('filtered_responses');
const VALID_RESPONSES = new Rate('valid_responses');

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8070').replace(/\/$/, '');
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '30s';
const RATE = Number(__ENV.RATE || 0);
const PREALLOCATED_VUS = Number(__ENV.PREALLOCATED_VUS || Math.max(VUS, RATE || VUS));
const MAX_VUS = Number(__ENV.MAX_VUS || Math.max(PREALLOCATED_VUS, VUS));
const LMT_PERCENT = Number(__ENV.LMT_PERCENT || 0);
const BLOCKED_IP_PERCENT = Number(__ENV.BLOCKED_IP_PERCENT || 0);

const DOMAINS = ['espn.com', 'cnn.com', 'nytimes.com', 'reddit.com'];
const USER_AGENTS = [
    'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)',
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)',
    'Mozilla/5.0 (Linux; Android 14)',
];

function buildOptions() {
    const base = {
        summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
        thresholds: {
            checks: ['rate>0.99'],
            http_req_failed: ['rate<0.01'],
            valid_responses: ['rate>0.99'],
        },
    };

    if (RATE > 0) {
        return {
            ...base,
            scenarios: {
                receiver: {
                    executor: 'constant-arrival-rate',
                    rate: RATE,
                    timeUnit: '1s',
                    duration: DURATION,
                    preAllocatedVUs: PREALLOCATED_VUS,
                    maxVUs: MAX_VUS,
                },
            },
        };
    }

    return {
        ...base,
        vus: VUS,
        duration: DURATION,
    };
}

export const options = buildOptions();

function trafficClass() {
    return ((__VU * 997) + __ITER) % 100;
}

function buildPayload() {
    const profile = trafficClass();
    const domain = DOMAINS[(__VU + __ITER) % DOMAINS.length];
    const userAgent = USER_AGENTS[(__VU + __ITER) % USER_AGENTS.length];

    let lmt = 0;
    let ip = `1.2.${(__VU % 250) + 1}.${(__ITER % 250) + 1}`;

    if (profile < LMT_PERCENT) {
        lmt = 1;
    } else if (profile < LMT_PERCENT + BLOCKED_IP_PERCENT) {
        ip = `10.10.${(__VU % 250) + 1}.${(__ITER % 250) + 1}`;
    }

    return JSON.stringify({
        id: `req-${__VU}-${__ITER}`,
        site: { id: `site-${__VU % 10}`, domain },
        device: { ip, ua: userAgent, lmt },
    });
}

export default function () {
    const res = http.post(`${BASE_URL}/bid-request`, buildPayload(), {
        headers: { 'Content-Type': 'application/json' },
        tags: { target: BASE_URL },
    });

    const isValid = res.status === 200 || res.status === 204;
    VALID_RESPONSES.add(isValid);
    if (res.status === 200) {
        ACCEPTED_RESPONSES.add(1);
    } else if (res.status === 204) {
        FILTERED_RESPONSES.add(1);
    }

    check(res, {
        'is legitimate response (200 or 204)': () => isValid,
    });
}
