// 장바구니 → 체크아웃 → 다건 주문 변환 부하 테스트
// 사용자별 장바구니 구성 → 체크아웃 한번에 → 주문 + 재고 차감 + Outbox 이벤트 발행
// 까지의 end-to-end 경로 검증.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const checkoutDuration = new Trend('checkout_duration_ms', true);

export const options = {
    vus: 50,
    duration: '3m',
    thresholds: {
        http_req_duration: ['p(95)<1000', 'p(99)<2000'],
        http_req_failed: ['rate<0.05'],
        checkout_duration_ms: ['p(95)<1000'],
    },
};

const PRODUCTS = [
    { productId: 1, variantId: 101 },
    { productId: 2, variantId: 102 },
];

export default function () {
    const userId = 10000 + __VU; // VU 별 고유 사용자

    // 1) 카트에 1~2 개 항목 추가
    for (const p of PRODUCTS) {
        http.post(`${BASE_URL}/users/${userId}/cart/items`, JSON.stringify({
            productId: p.productId,
            variantId: p.variantId,
            quantity: 1,
        }), { headers: { 'Content-Type': 'application/json' } });
    }

    // 2) 체크아웃 — 다건 주문으로 변환
    const start = Date.now();
    const res = http.post(`${BASE_URL}/users/${userId}/cart/checkout`);
    checkoutDuration.add(Date.now() - start);

    check(res, {
        'checkout 200 OR 409(재고부족)': (r) => r.status === 200 || r.status === 409,
        'checkout response has orderId': (r) => r.status !== 200 || (r.json() && r.json().orderId !== undefined),
    });

    sleep(2); // 사용자 행동 모방
}
