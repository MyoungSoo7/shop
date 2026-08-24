# ADR 0013 — 분할 결제(Tenders) + 역환불

- 상태: Accepted
- 일자: 2026-03-23

## 컨텍스트

한 주문을 단일 결제수단으로만 받는 모델은 현실의 결제를 표현하지 못한다. 사용자는 흔히
**포인트 + 상품권 + 카드**처럼 여러 수단을 섞어 한 번에 결제한다(예: 50,000원 =
POINT 5,000 + GIFT_CARD 10,000 + CARD 35,000).

이때 두 가지가 까다롭다:

1. **합계 불변식** — 모든 결제수단(tender)의 금액 합이 결제 총액과 정확히 일치해야 한다.
2. **환불 정합성** — 부분 환불 시 어떤 tender 부터 얼마를 돌려줄지, 그리고 외부 PG 환불과
   내부 잔액 복원이 섞인 상황에서 **DB 상태와 PG 실거래가 어긋나지 않게** 처리해야 한다.

특히 환불을 하나의 트랜잭션 안에서 tender 루프로 PG 를 여러 번 호출하면, 두 번째 tender PG
환불이 실패할 때 트랜잭션이 통째로 롤백되어 **이미 PG 에서 환불된 첫 tender 의 DB 상태까지
되돌아가** DB("환불 안 됨") vs PG("환불됨") 정합성이 깨진다.

## 결정

결제를 **Payment + 다수의 `PaymentTender`** 로 모델링하고, 환불은 **sequence 역순**으로 tender
별 **독립 트랜잭션**에서 처리한다.

### 1. 도메인 모델

- `PaymentTender` 는 Payment 의 자식 — `type`(`TenderType`), `amount`, `refundedAmount`,
  `pgTransactionId`, `status`(`TenderStatus`), `sequence` 보유.
- `TenderType.usesExternalPg()` 로 외부 PG(CARD/KAKAO_PAY/...) 와 내부 잔액(POINT/GIFT_CARD)을
  구분 — 내부 잔액은 PG 호출 없이 즉시 캡처.
- `PaymentDomain.isSplit()` = `!tenders.isEmpty()`. `validateTenderSum()` 이 tender 합계 =
  amount 불변식을 검증.
- 생성은 `CreateSplitPaymentService`(최소 2 tender). 외부 PG tender 는 `PgRouter` 경유
  authorize/capture, 내부 잔액은 즉시 캡처.

### 2. 역환불 계획 — `planRefundFromTenders`

`PaymentDomain.planRefundFromTenders(total)` 가 tender 를 **sequence 역순**(큰 sequence 먼저)으로
순회하며 `TenderRefundPlan(tender, portion)` 리스트를 만든다. 외부 PG(CARD)가 먼저 취소되어
실거래가 사라지고, 내부 잔액(POINT/GIFT_CARD)은 마지막에 복원한다 — 실패해도 운영자가
수동 복원 가능. 전체 잔여 환불 가능액 초과는 거부한다.

### 3. tender 별 독립 커밋 — `REQUIRES_NEW`

`RefundSplitPaymentService` 는 트랜잭션을 들지 않는 오케스트레이터다. 계획을 세운 뒤 tender
마다 `TenderRefundExecutor.refundTender`(`@Transactional(REQUIRES_NEW)`)를 호출해 건별로 독립
커밋한다. 각 executor 트랜잭션은 부모 결제 행을 `FOR UPDATE` 로 잠가 `refundedAmount` lost
update 와 tender 초과 환불을 직렬화로 차단하고, `PaymentTender.addRefund` 가 잔여 초과를
재검증한다. 중간 실패 시 앞선 tender 환불은 PG 실거래와 일치하게 보존되고 뒤 tender 는
중단되어 운영자 대사 대상으로 남는다. 계획된 tender 가 모두 성공한 경우에만
`finalizeRefund` 가 `PaymentRefunded` 이벤트를 1회 발행(정산 조정 트리거).

## 결과

### 좋아지는 점
- 멀티 수단 결제를 1 주문으로 표현 — 현실 결제 모델링
- 역순 환불로 외부 PG 실거래를 먼저 취소 → 운영 사고 방지
- tender 별 `REQUIRES_NEW` 독립 커밋으로 부분 실패가 앞선 환불을 롤백하지 않음 — DB-PG 정합성 유지
- 외부 I/O 동안 DB 커넥션을 장기 점유하지 않음

### 트레이드오프 / 리스크
- 부분 실패 시 일부만 환불된 중간 상태가 남아 운영자 대사가 필요(이벤트 미발행으로 격리)
- 생성 경로의 외부 PG 중간 실패 보상(Saga)은 현재 단순화 — 별도 보상 설계 여지
- tender 합계 불변식·sequence 정렬을 도메인이 항상 강제해야 함

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **Payment + Tenders, 역순 REQUIRES_NEW (본 결정)** | ✓ | 합계 불변식 + DB-PG 정합성 동시 충족 |
| 단일 결제수단만 허용 | ✗ | 현실 결제(혼합 수단) 미표현 |
| 전체 환불을 단일 트랜잭션 루프로 PG 호출 | ✗ | 중간 실패 시 PG-DB 정합성 붕괴 |
| 정순(sequence 오름차순) 환불 | ✗ | 내부 잔액 먼저 복원 → 외부 PG 미취소 잔존 위험 |

## 참조

- [0006 — Toss PG 호출 회복탄력성 (Resilience4j)](0006-resilience4j-tosspg.md)
- [0010 — 다중 PG 라우팅 + Bulkhead](0010-multi-pg-routing-and-bulkhead.md)
