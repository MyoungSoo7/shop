// 결제 승인 (CAPTURE) 부하 테스트
// 가장 hot 한 경로. PG (mock) → CAPTURE → Outbox → Kafka → 정산 트리거.
// p99 latency 가 임계값을 넘으면 Resilience4j CB / Outbox 폴러 폴링 주기 점검.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const captureDuration = new Trend('payment_capture_duration_ms', true);
const cbOpenCounter = new Counter('circuit_breaker_open_responses');

export const options = {
    stages: [
        { duration: '30s', target: 200 },
        { duration: '1m', target: 200 },
        { duration: '15s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<500', 'p(99)<1000'],
        http_req_failed: ['rate<0.001'],          // 0.1% 미만
        payment_capture_duration_ms: ['p(95)<500'],
    },
};

export default function () {
    // 사전 조건: orderId 1 ~ 100000 의 결제가 AUTHORIZED 상태로 시드되어 있다고 가정.
    // 운영급 부하 테스트는 setup() 으로 paymentId 풀을 만들어 사용.
    const paymentId = 1 + Math.floor(Math.random() * 100000);

    const start = Date.now();
    const res = http.post(`${BASE_URL}/payments/${paymentId}/capture`);
    captureDuration.add(Date.now() - start);

    if (res.status === 503 && res.body && res.body.includes('일시 장애')) {
        cbOpenCounter.add(1);
    }

    check(res, {
        'capture 200 OR 4xx (이미 처리/존재안함)': (r) => r.status === 200 || (r.status >= 400 && r.status < 500),
    });

    sleep(0.5);
}
