---
name: coupon-money-rules
description: 쿠폰·프로모션 머니 세이프티 — FLOOR 절사, 상한 클램프 순서, 1인 1매, 원자적 사용량 증가, 환불 안분. 쿠폰/할인 코드를 작성·리뷰할 때 로드.
---

# Coupon Money Rules (쿠폰·프로모션 정합성)

상시 할인·쿠폰은 패션 커머스의 매출 방어 수단이자 마진 누수 1순위다.
이 코드베이스의 쿠폰 계산·동시성 규칙을 그대로 지켜라.

## 할인 계산 (Coupon.calculateDiscount)

계산 순서가 곧 규칙이다 — 순서를 바꾸면 금액이 달라진다:

1. **원시 할인**: `CouponType` enum-Strategy
   - `FIXED`(정액): `min(discountValue, orderAmount)` — 주문액 초과 할인 금지
   - `PERCENTAGE`(정률): `orderAmount × value ÷ 100` **FLOOR 절사**, value > 100 은 생성 시점 거부
2. **상한 클램프**: `maxDiscountAmount` 가 있으면 원시 할인에 `min` 적용 — **절사 이후**에 클램프
3. 검증(`validate`): active / `usedCount < maxUses` / 기간 / `minOrderAmount` / 대상(`appliesTo` 상품·카테고리)

절사 정책을 HALF_UP 으로 "고치자"는 제안은 반려 — FLOOR 는 고객 유리 방향이 아니라
**플랫폼 마진 보호 방향**으로 일관된 정책이다. 기대값은 MCP `coupon_simulate` 로 재확인하라.

## 환불 안분 (calculateDiscountForRefund)

부분 환불 시 할인 몫 = `총할인 × 환불액 ÷ 주문액` **FLOOR**.
현금 환불액 = 환불액 − 안분 할인. 안분을 생략하면 고객이 할인받은 금액까지 현금으로 돌려받는다(마진 누수).

## 동시성 — 초과 사용 방지 2단 방어

1. **전역 한도**: `incrementUsageIfAvailable` 원자적 조건부 UPDATE (`usedCount < maxUses` 조건).
   `usedCount` 를 읽어서 +1 저장하는 코드는 **초과 사용 버그** — 절대 금지.
2. **1인 1매**: `coupon_usages UNIQUE(coupon_id, user_id)`. UNIQUE 위반 시 롤백 + "이미 사용" 멱등
   응답 (`CouponService.useCoupon`). 소프트 체크(`hasUserUsedCoupon`)는 UX 용 사전 안내일 뿐이다.

## 주문 결합 — 할인 비영속 주의

`orders` 테이블에 **할인 컬럼이 없다**. `amount = subtotal − discount` 로 저장되고
할인액은 `Σ line_amount − amount` 로 **역산**한다 (`CreateMultiItemOrderService`, `Order.createMultiItem`).

- 쿠폰 정합성 검증 코드는 order_items subtotal 재계산 기반이어야 한다.
- "주문에 할인액 컬럼 추가" 요구가 오면 기존 역산 로직과의 이중 진실 원천 문제를 먼저 지적하라.
- 쿠폰 적용 주문의 환불·역정산 검증 시 할인 안분(위)과 주문 amount 역산을 함께 확인하라.

## 테스트 최소 케이스 (쿠폰 로직 수정 시)

```java
// 1) PERCENTAGE 절사: 33,333원 × 10% → 3,333원 (FLOOR)
// 2) 상한 클램프: 100,000원 × 20% = 20,000 → max 10,000 이면 10,000
// 3) FIXED 초과: 3,000원 주문에 5,000원 쿠폰 → 3,000
// 4) 환불 안분: 할인 3,333 · 환불 10,000/33,333 → 안분 999 (FLOOR), 현금 9,001
// 5) 동시 사용: 같은 (coupon, user) 2 요청 → 1건만 성공 (UNIQUE)
```

기대값은 손계산 대신 MCP `coupon_simulate` 로 재확인하라.
