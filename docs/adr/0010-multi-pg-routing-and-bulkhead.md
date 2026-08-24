# ADR 0010 — 다중 PG 라우팅 + Bulkhead

- 상태: Accepted
- 일자: 2026-02-24

## 컨텍스트

단일 PG 만 연동하면 그 PG 의 장애가 곧 매출 정지로 직결된다. 실제 결제팀은 2~4 개 PG 를
동시 연동하고 결제수단·수수료·건강도에 따라 동적으로 라우팅한다. order-service 도
`PaymentGateway` 열거형으로 **TOSS / KCP / NICE / INICIS**(+ 테스트용 MOCK)를 지원해야 한다.

여기서 두 문제가 생긴다:

1. **선택** — authorize 시점에 결제수단·금액·PG health 를 보고 적합한 PG 를 골라야 하고,
   1순위 PG 가 죽으면 자동으로 다른 PG 로 넘어가야 한다.
2. **격리** — 한 PG 의 장애가 다른 PG 호출에 전이되면 다중 PG 의 의미가 없다. PG 별 회복탄력성
   상태가 서로 독립이어야 한다(Bulkhead).
3. **이력 일관성** — authorize 를 처리한 PG 와 capture/refund 를 처리하는 PG 가 달라지면 안 된다.
   후속 작업은 반드시 원래 거래를 처리한 PG 로 가야 한다.

## 결정

`PgRouter`(아웃바운드 어댑터)가 PG 선택을 전담하고, PG 별 독립 CircuitBreaker 로 격벽을 치며,
거래 ID prefix 로 후속 작업의 PG 를 식별한다. 도메인/UseCase 는 PG 를 모르고 `PgClientPort` 만 본다.

### 1. 라우팅 — `PgRouter.selectFor(amount, paymentMethod)`

우선순위 3 단계로 후보를 고른다. 각 후보는 `supports(method)` && `isHealthy()` 를 만족해야 한다.

1. **고액 거래 우선** — `amount ≥ highAmountThreshold`(기본 1,000,000) 면 `highAmountPreferred`(기본 NICE).
2. **결제수단별 1순위** — `primaryByMethod`(예: CARD→TOSS, KAKAO_PAY→NICE, BANK_TRANSFER→KCP).
3. **fallback 체인** — 좌→우 순회(`[TOSS, NICE, KCP, INICIS]`)로 살아있는 첫 PG.

정책은 `PgRoutingProperties`(`app.pg.routing`)로 외부화되어 코드 변경 없이 바뀐다. 후보가
전무하면 `IllegalStateException`. 각 결정은 `pg.routing.requests` 카운터(provider·reason·method 태그)로 노출.

### 2. Bulkhead — PG 별 독립 CircuitBreaker

각 PG 어댑터(`TossPgAdapter` 등)는 자신의 CB 인스턴스(`tossPg`/`kcpPg`/`nicePg`/`inicisPg`)
상태를 `isHealthy()` 로 노출한다. 모두 `pgDefault` 베이스 설정을 공유하되 인스턴스는 분리되어,
한 PG 가 실패율 50% 초과로 OPEN 되어도 나머지 PG 의 CB 는 영향받지 않는다. `PgRouter` 는
OPEN 인 PG 를 후보에서 제외(`!isHealthy()`)하고 다음 후보로 넘어간다.

### 3. 거래 ID prefix 로 동일 PG 라우팅

`pgTransactionId` 에 PG prefix 를 인코딩한다 — 예 `"TOSS:abc-123"`, `"KCP:..."`
(`PaymentGateway.prefix()` + `TRANSACTION_ID_DELIMITER`). capture/refund 시
`PgRouter.resolveByTransactionId` 가 `PaymentGateway.fromTransactionId` 로 prefix 를 해석해
원래 PG 어댑터로 위임한다. DB 컬럼 추가 없이 거래 ↔ PG 매핑 이력을 보존하며, 인식 불가
prefix 는 MOCK 으로 폴백해 과거 데이터와 호환된다.

## 결과

### 좋아지는 점
- 단일 PG 장애가 매출 정지로 직결되지 않음 — fallback 체인으로 자동 우회
- PG 별 CB 격벽으로 장애 전이 차단(Bulkhead)
- 라우팅 정책을 설정으로 외부화 — 무중단 정책 변경
- prefix 인코딩으로 후속 작업이 항상 동일 PG 로 — 환불·매입 정합성 보장

### 트레이드오프 / 리스크
- PG 추가마다 어댑터·CB 인스턴스·라우팅 설정을 함께 늘려야 함
- 실 운영 시 각 PG 의 인증·서명·응답 포맷 차이를 어댑터가 흡수해야 함(현재 일부 Mock)
- prefix 규칙 변경은 기존 거래 ID 호환성에 직접 영향 — 신중한 마이그레이션 필요

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **PgRouter + per-PG CB + prefix (본 결정)** | ✓ | 선택·격리·이력 일관성을 한 번에 해결 |
| 단일 PG 직결 | ✗ | PG 장애 = 매출 정지 |
| PG 선택을 도메인/UseCase 에 둠 | ✗ | 인프라 결정이 도메인을 오염 — 헥사고날 위배 |
| 공유 단일 CircuitBreaker | ✗ | 한 PG 장애가 전 PG 차단 — Bulkhead 부재 |
| 거래-PG 매핑 별도 컬럼 | △ | 가능하나 prefix 인코딩이 스키마 변경 0 으로 더 단순 |

## 참조

- [0006 — Toss PG 호출 회복탄력성 (Resilience4j)](0006-resilience4j-tosspg.md)
