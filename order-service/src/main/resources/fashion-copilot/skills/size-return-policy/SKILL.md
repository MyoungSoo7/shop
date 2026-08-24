---
name: size-return-policy
description: 사이즈·핏 반품/환불 규칙 — 환불 3-Phase, 멱등키, 부분환불, 분할결제 역순, 할인 안분. 환불·반품·교환 코드를 작성하거나 반품 이상을 조사할 때 로드.
---

# Size & Return Policy (패션 반품·환불 규칙)

패션 이커머스의 최대 비용 축은 **사이즈·핏 반품**이다. 반품 = 환불 + 재고 복원 + (할인 시) 안분이
전부 얽힌 최다 빈도 금전 경로 — 이 코드베이스의 환불 파이프라인 규칙을 그대로 지켜라.

## 환불 3-Phase 구조 (RefundPaymentUseCase)

`order-service/.../payment/application/RefundPaymentUseCase.java`

1. **Phase 0 — 락 밖 스냅샷**: 예비 검증 + 멱등 단축 반환 (동일 Idempotency-Key 의
   COMPLETED 환불이 있으면 PG 재호출 없이 즉시 반환).
2. **Phase 1 — REQUESTED 독립 커밋**: `RefundLifecycle.begin` (REQUIRES_NEW) 이 락을 잡기
   **전에** 환불 이력을 커밋한다. FK KEY SHARE ↔ FOR UPDATE 교착 회피가 목적 — 이 순서를 바꾸지 마라.
3. **Phase 2 — FOR UPDATE 재확정**: `loadByIdForUpdate` 비관적 락 안에서 환불 가능액을
   **다시** 계산한 뒤 PG 를 호출한다. Phase 0 스냅샷을 신뢰하고 락 안 재검증을 생략하면 동시 환불에 뚫린다.
4. **Phase 3 — 완료 처리**: COMPLETED + `addRefundedAmount`, **전액 도달 시에만**
   `payment.refund()` + 주문 REFUNDED + `PaymentRefunded` 이벤트 발행.

## 절대 규칙

- **멱등키**: 전액 환불 = 자동키 `payment-{id}-full`. 부분 환불 = 호출자 필수
  (`MissingIdempotencyKeyException`). 같은 키는 PG 멱등키로도 전달된다.
- **초과 금지**: 요청액 > `refundableAmount` 이면 `RefundExceedsPaymentException`. 우회 경로 금지.
- **주문 상태**: 부분 환불은 주문 상태 불변. 전액 도달 시에만 REFUNDED 전이.
- **교환 = 환불 + 재주문**: 사이즈 교환을 "결제 금액 유지 + 재고만 스왑"으로 구현하지 마라 —
  재고·정산·원장 모두 어긋난다. 기존 건 환불 → 신규 주문 생성이 원칙.
- **실패 처리**: `RefundLifecycle.fail` 이 FAILED + 재시도 백오프 + `lemuel.ops.payment.failed`
  ops signal 을 담당한다. 새 실패 분기를 만들면 이 경로를 재사용하라.

## 분할결제(split) 환불 — RefundSplitPaymentService

- tender 는 **sequence 역순**(외부 PG=CARD 먼저)으로 환불한다. 순서를 바꾸면 내부 잔액 복원 후
  외부 PG 실패 시 복구가 꼬인다.
- tender 별 `refundTender` 는 REQUIRES_NEW 독립 커밋 + tender 멱등키 `tender-{id}-{amount}`.
- ⚠️ **알려진 공백**: split 경로는 Refund 엔티티를 만들지 않아 `refundId=null` → 원장 역분개가
  생략된다 (`TenderRefundExecutor.finalizeRefund`). 이 경로를 수정할 때 원장 완결 로드맵(설계서 §8)을 확인하라.

## 할인(쿠폰) 안분

부분 환불 시 쿠폰 할인 몫은 `Coupon.calculateDiscountForRefund` —
`총할인 × 환불액 ÷ 주문액` **FLOOR**. 현금 환불액 = 환불액 − 안분 할인. 기대값은 MCP
`coupon_simulate`(refundAmount 인자)로 재확인하라.

## 반품 사유 분석의 현재 한계

`Refund.reason` 은 유형(`FULL_REFUND`/`PARTIAL_REFUND`)·실패 메시지 겸용이라 **사이즈/핏 반품
사유 분석이 불가능**하다. 반품 사유 통계 요구가 오면 사유 enum + 컬럼 신설(설계서 §8 Phase 2)을
먼저 제안하라 — 기존 reason 필드에 사유 문자열을 덧쓰는 방식은 반려.

## 진단 순서 (반품 이상 의심 시)

1. MCP `refund_recon(date)` — 일자별 캡처 대비 환불 금액·건수
2. MCP `refund_health()` — 실패율·멱등키 재사용(재시도 폭풍)·처리시간
3. 개별 건은 `refund_simulate` 로 기대값 재계산 후 실제와 대조
