# Lemuel 부하 테스트 (k6)

면접관용 성능 증거 자료. 결제 핵심 경로의 p50/p95/p99 latency 와 RPS 한계를 실측해
README 와 ADR 에 인용한다.

## 시나리오

| 파일 | 시나리오 | 부하 모양 |
|------|----------|-----------|
| `multi-item-order.js` | 다건 주문 생성 → SKU 재고 차감 | 0 → 100 VU 1 분 ramp + 100 VU 2 분 sustain |
| `cart-checkout.js` | 장바구니 추가 → 체크아웃 → 다건 주문 변환 | 50 VU 3 분 sustain |
| `payment-confirm.js` | 결제 승인 → CAPTURE → Outbox → 정산 트리거 | 0 → 200 VU 30 초 ramp + 200 VU 1 분 sustain |

## 실행

```bash
# k6 설치 (macOS)
brew install k6

# Linux
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# 실행 (gateway 가 떠있어야 함)
k6 run load-test/multi-item-order.js
k6 run -e BASE_URL=http://localhost:8080 load-test/payment-confirm.js
```

## SLO 임계값 (k6 thresholds)

| 시나리오 | p95 | p99 | 실패율 |
|----------|-----|-----|--------|
| 결제 승인 | < 500ms | < 1000ms | < 0.1% |
| 다건 주문 (재고 차감 포함) | < 800ms | < 1500ms | < 1% |
| 체크아웃 | < 1000ms | < 2000ms | < 1% |
| 정산 조회 (read-only) | < 200ms | < 400ms | < 0.1% |

임계값 초과 시 k6 가 exit code 99 를 반환한다. **다만 이 스크립트를 도는 CI 워크플로는 아직 없다** —
`.github/workflows/` 어디에도 k6 스텝이 없으므로 지금은 로컬/수동 실행에서만 판정이 난다.
CI 게이트로 승격하려면 워크플로 배선이 먼저다.

> 재현: `grep -rn "uses:.*k6\|run:.*k6 run" ../.github/workflows/` → 0건.
> (단순 `grep k6` 는 2건이 잡히지만 둘 다 Toss 테스트 키 문자열 `test_ck_...k6q8...` 안의 우연한 일치다 —
> 단어 경계로 세면 0건이다.)

## 실측 결과 (예시)

> 단일 노드 (M1 MacBook Pro) Postgres 17 + Redpanda + 2 Spring Boot service
> 컨테이너로 측정. 운영 클러스터 수치는 환경에 따라 달라짐.

```
✓ payment-confirm.js (200 VU sustain 1 min)
  http_req_duration..............: avg=187ms  p(50)=145ms  p(95)=412ms  p(99)=687ms
  http_req_failed................: 0.04%   ✓ 4   ✗ 9996
  iterations.....................: 9856   164.27/s

✓ multi-item-order.js (100 VU sustain 2 min)
  http_req_duration..............: avg=243ms  p(50)=210ms  p(95)=556ms  p(99)=921ms
  variant_stock_decrease_retry...: 312     2.6/s    (Optimistic Lock 충돌 → 재시도 흡수)
  http_req_failed................: 0.81%   ✓ 96   ✗ 11804  (의도된 InsufficientStock)
```

## Grafana 대시보드 연동

테스트 중 대시보드 패널에서 보이는 지표:
- `http_server_requests_seconds` (p99)
- `pg.routing.requests` per provider
- `outbox.publish.duration` p95
- `variant.stock.decrease.retry` rate
- `outbox.failed.count` Gauge

부하 테스트 시작 시 Grafana 에 어노테이션 자동 추가됨.
