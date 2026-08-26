---
name: order-commerce-rules
description: 커머스 도메인 핵심 규칙 — 주문/결제/텐더 상태머신, 환불 동시성·멱등·초과차단, 재고 조건부 UPDATE, 쿠폰·멤버십, Outbox 이벤트. order-service 로직을 작성·수정·리뷰할 때 로드.
---

# 커머스 도메인 규칙 (order-service)

거래 컨텍스트(user·order·payment·cart·shipping·product·coupon·review). 금액·상태·재고·환불이
실제 돈·재고와 직결되므로 상태전이·동시성·멱등을 도메인이 강제한다. money-safety, idempotency-and-events 참조.

## 상태머신 (setter 직접 변경 금지 — 전이 메서드 경유)

```
Order:   CREATED → PAID → SHIPPING_PENDING → IN_TRANSIT → DELIVERED
              ↘ CANCELLATION_REQUESTED → CANCELLATION_APPROVED → CANCELED
              ↘ REFUND_REQUESTED → REFUNDED   (종단: CANCELED·REFUNDED)
              ↘ EXCHANGE_REQUESTED → SHIPPING_PENDING (교환품 재배송 — 되돌아간다)
                                   ↘ REFUND_REQUESTED / REFUNDED (교환 불가 판정 시)
ReturnRequest: REQUESTED → APPROVED → COLLECTED → COMPLETED
                        ↘ REJECTED / WITHDRAWN  (종단: COMPLETED·REJECTED·WITHDRAWN)
Payment: READY → AUTHORIZED → CAPTURED → REFUNDED   (AUTHORIZED→FAILED/CANCELED)
Tender:  PENDING → AUTHORIZED → CAPTURED → REFUNDED  (→FAILED)
```

- 전이는 **`Order.transitionTo(target)`**(`OrderStatus.canTransitionTo` 화이트리스트) / **`PaymentDomain`** 비즈니스
  메서드(authorize/capture/cancel/refund)로만. 동일상태 재적용은 멱등 no-op, 비허용 전이는 `IllegalStateException`.
- CAPTURED = 정산 대상. `capture` 이후 취소 불가(환불 경로 사용).
- IN_TRANSIT/DELIVERED 도달 시 `shipped=true` 영구기록 — 이후 환불 배송비 차감 근거.
- `REFUND_COMPLETED` 는 @Deprecated(레거시 호환) — 신규 전이는 `REFUNDED` 하나로.
- **교환은 환불과 별도 상태**다. 교환은 배송 흐름으로 되돌아가지만 `REFUND_REQUESTED` 에서 갈 수 있는
  곳은 `REFUNDED` 뿐이라, 교환을 환불 신청으로 받아 두면 되돌아갈 길이 막힌다.
- **신청 상태와 주문 상태는 별개 축**이다(`OrderReturnRequest` ↔ `Order`). 반품 신청이 REJECTED 여도
  주문은 신청 직전 상태로 돌아갈 뿐이라, 두 축을 한 enum 으로 합치면 "거절된 배송중 주문"을 표현할 수 없다.
  예외도 나눠 둔다 — `InvalidReturnRequestStateException` 은 신청 상태, `InvalidOrderStateException` 은 주문 상태.
- 신청 레코드는 주문당 **열린 것 하나**(부분 유니크 인덱스 `ux_order_return_requests_open`). 서비스의
  `findOpenByOrderId` 검사는 사람이 읽을 메시지용이고, 동시 요청의 최종 방어선은 DB 인덱스다.
- **재고 원복은 회수 확인(COLLECTED) 시점**에만 — `restoreStockOnReturn`. 배송 후 환불은 물건이 고객
  손에 있어 되돌리지 않는다. 승인 시점에 원복하면 오지 않은 물건이 판매 가능 재고가 된다.
- 무통장·가상계좌(`PaymentDomain.awaitsDeposit()`)는 PG 로 되돌릴 길이 없어 **환불 계좌**가 필요하다.
  필요 여부는 접수 시점에 굳혀 `refund_account_required` 로 보관한다(대기열이 결제를 다시 묻지 않도록,
  그리고 접수 후 결제가 바뀌어도 고객이 계좌를 낸 근거와 판정이 어긋나지 않도록).
- `TenderType`: CARD/KAKAO_PAY/…/VIRTUAL_ACCOUNT 은 `usesExternalPg=true`, POINT/GIFT_CARD 은 내부잔액(PG 없이 즉시 캡처).
  환불은 **외부 PG 먼저, 내부잔액 마지막 복원**.

## 환불 (RefundPaymentUseCase — 가장 조심할 곳)

- 격리수준 **READ_COMMITTED**(REPEATABLE_READ 금지 — begin 이 독립커밋한 REQUESTED 행을 UPDATE tx 가 봐야 함).
- 3단: ① 락 밖 스냅샷 예비검증+멱등 단축반환 → ② `RefundLifecycle.begin` 시도이력 독립커밋(FK 교착 방지)
  → ③ `loadByIdForUpdate` 비관적 락 안에서 권위 재검증 → **PG 성공 후에만** 결제/주문/이벤트 원자 커밋.
  PG 실패 시 FAILED 독립 tx + 예외로 공유 tx 롤백("성공 시에만 확정", 유령환불 방지).
- **Idempotency-Key**: 전액환불(amount=null)은 `payment-{id}-full` 자동생성, **부분환불은 필수**(없으면
  `MissingIdempotencyKeyException`). effectiveKey 를 PG 멱등키로 전달 → 재시도 PG 이중환불 차단.
- **초과환불 차단**: `plannedAmount > (amount − refundedAmount)` → `RefundExceedsPaymentException`. 스냅샷·락안 이중검증.
- **전액환불 도달 시에만** order REFUNDED 전이(부분환불은 주문상태 불변).
- `RefundPolicy.forOrder`: 배송 전 → 전액환불, 배송 후 → 배송비 차감(clamp). `RoundingMode.FLOOR`(원 단위 버림).
- Refund 재시도: `MAX_RETRIES=5`, 백오프 `{1,5,15,60,180}분`. COMPLETED 는 fail/abandon 불가.

## 재고 (조건부 UPDATE — read-modify-write 금지)

- **원자적 조건부 UPDATE**: `SET stock=stock-q WHERE id=? AND stock>=q AND status<>DISCONTINUED` —
  검증+차감+매진전이를 row 락 안 원자 처리. affected rows=0 이면 거절. 낙관락+재시도 아님(핫딜에도 초과판매 0).
- `decreaseInNewTransaction`(REQUIRES_NEW)로 결제 tx 안에서 독립 커밋. 0 도달 시 `OUT_OF_STOCK` 자동전이.
- 부족 → `InsufficientStockException`(+ops 신호 STOCK_DEPLETED best-effort). 낙관락 충돌 → `StockConcurrencyException`(409).
- `load 후 setStock save` 패턴 금지 — lost update.

## 쿠폰·멤버십

- `Coupon`: code 대문자·trim, discountValue>0, PERCENTAGE 는 100% 상한. validate(활성/사용횟수/기간/최소주문액)
  → calculateDiscount(maxDiscount clamp). 환불 시 안분 `totalDiscount × 환불액/원주문액` **FLOOR**.
  타입 Strategy enum(FIXED/PERCENTAGE), 타깃 Strategy(ALL/PRODUCT/CATEGORY).
- `MembershipStatus`: PENDING→APPROVED→SUSPENDED, →REJECTED. `canUseService()`=APPROVED 만. 액션 APPROVE/REJECT/SUSPEND/REINSTATE.
  `MembershipApproval` 감사이력(userId/action/reason/processedBy) 남긴다.

## 이벤트·경계 (idempotency-and-events 참조)

- **Outbox 경유** 발행(직접 send 금지): `lemuel.payment.captured`/`.refunded`, `lemuel.order.created`,
  `lemuel.user.registered`, `lemuel.product.changed`. payload 금액은 `toPlainString()` 문자열화, traceparent 전파.
- `/internal/recon/**`(ADR 0020 self-totals): settlement 가 호출해 자기 DB 합계 비교(cross-DB 0).
  **`X-Internal-Api-Key`(InternalApiKeyFilter)로만** 보호 — gateway route·JWT 로 노출 금지. settlement 의 INTERNAL_API_KEY 와 동일.

## 안티패턴 (발견 시 지적)

- `setStatus` 직접 호출로 상태머신 우회 / 종단(REFUNDED·CANCELED) 재전이 / `REFUND_COMPLETED` 신규 전이.
- 금액 double/float, 수동 amount 세팅(다건주문 amount=Σlineamount−discount 정합 위반), 정밀도 손실 직렬화.
- 환불을 락 없이 refundedAmount 갱신 / REPEATABLE_READ 격리 / PG 성공 전 확정.
- 재고 read-modify-write, 부분환불 Idempotency-Key 누락.
- ops 신호(`OpsSignalPort.emit`)에서 throw — best-effort, 실패 전파 절대 금지.
