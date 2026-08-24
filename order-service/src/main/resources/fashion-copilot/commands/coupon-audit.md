---
description: 쿠폰·프로모션 정합성 감사 — 할인 계산 교차검증 + 초과 사용 방어 점검
argument-hint: "[대상: 코드 경로 또는 쿠폰 조건, 생략 시 coupon 패키지 전체]"
---

`coupon-money-rules` skill 을 로드하고(skill 미지원 환경이면
`..n-money-rules/SKILL.md` 를 직접 읽어라), 쿠폰 감사를 수행하라.
대상: $ARGUMENTS (비어 있으면 order-service coupon 패키지 전체).

1. 대상 코드의 할인 계산 경로(`CouponType.rawDiscount` → `maxDiscountAmount` 클램프 →
   `validate`)가 skill 의 계산 순서와 일치하는지 확인하라.
2. 대표 케이스를 MCP `coupon_simulate` 로 교차검증하라 — 최소:
   PERCENTAGE 절사(33,333×10%→3,333), 상한 클램프, FIXED 주문액 초과, 환불 안분(FLOOR).
3. 동시성 방어 점검: `incrementUsageIfAvailable` 원자 경로 사용 여부,
   `usedCount` 직접 증감 코드 존재 여부, `UNIQUE(coupon_id, user_id)` 우회 여부.
4. 주문 결합: 할인이 orders 에 비영속(subtotal 역산)임을 전제로 검증 코드가 작성됐는지 확인하라.

보고: 위반 목록(severity 순) → 각 위반의 코드 위치 → simulate 교차검증 수치 → 수정 방향.
절사 정책(FLOOR)을 바꾸는 수정안은 제안하지 마라.
