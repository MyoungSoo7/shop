// 다건 주문 생성 부하 테스트
// 100 VU 가 동시에 SKU 변동이 있는 다건 주문을 생성. 재고 차감의 Optimistic Lock
// 재시도가 어떻게 흡수되는지 + p99 latency 가 어떻게 변하는지 확인.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || ''; // 운영 환경은 JWT 필수

// 사전 조건: products 1..3, variants 101..103 가 시드 데이터로 존재
const PRODUCTS = [
    { productId: 1, variantId: 101 },
    { productId: 2, variantId: 102 },
    { productId: 3, variantId: 103 },
];

// 커스텀 메트릭
const insufficientStockCounter = new Counter('insufficient_stock_responses');
const stockConcurrencyConflict = new Counter('stock_concurrency_conflicts');
const orderCreatedDuration = new Trend('order_created_duration_ms', true);

export const options = {
    stages: [
        { duration: '1m', target: 100 }, // ramp-up
        { duration: '2m', target: 100 }, // sustain
        { duration: '30s', target: 0 },  // ramp-down
    ],
    thresholds: {
        http_req_duration: ['p(95)<800', 'p(99)<1500'],
        http_req_failed: ['rate<0.1'],            // 10% 미만 (재고 부족 포함)
        order_created_duration_ms: ['p(95)<800'],
    },
};

function pickRandomLines() {
    const numLines = 1 + Math.floor(Math.random() * 3); // 1~3 라인
    const lines = [];
    for (let i = 0; i < numLines; i++) {
        const p = PRODUCTS[Math.floor(Math.random() * PRODUCTS.length)];
        lines.push({
            productId: p.productId,
            variantId: p.variantId,
            quantity: 1 + Math.floor(Math.random() * 3),
        });
    }
    return lines;
}

export default function () {
    const userId = 1 + Math.floor(Math.random() * 1000);
    const payload = JSON.stringify({
        userId: userId,
        lines: pickRandomLines(),
    });

    const headers = { 'Content-Type': 'application/json' };
    if (AUTH_TOKEN) headers['Authorization'] = `Bearer ${AUTH_TOKEN}`;

    const start = Date.now();
    const res = http.post(`${BASE_URL}/orders/multi`, payload, { headers });
    orderCreatedDuration.add(Date.now() - start);

    if (res.status === 409 && res.body && res.body.includes('재고')) {
        insufficientStockCounter.add(1);
    } else if (res.status === 409 && res.body && res.body.includes('재시도')) {
        stockConcurrencyConflict.add(1);
    }

    check(res, {
        'status is 201 or 409 (재고 부족 의도된 실패)': (r) => r.status === 201 || r.status === 409,
    });

    sleep(1);
}
