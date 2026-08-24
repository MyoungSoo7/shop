---
description: 반품·환불 경로 감사 — 일자별 환불 대사 + 실패율/멱등 재사용 + 개별 건 재계산
argument-hint: "[date: YYYY-MM-DD, 생략 시 오늘]"
---

`size-return-policy` skill 을 로드하고(skill 미지원 환경이면
`..return-policy/SKILL.md` 를 직접 읽어라), 반품·환불 감사를 수행하라.
대상 날짜: $ARGUMENTS (비어 있으면 오늘).

1. MCP `refund_recon(date)` — 캡처 대비 환불 금액·건수. 환불액 급증이면 이상 신호.
2. MCP `refund_health()` — 실패율·`refund.idempotency_key_reuse`(재시도 폭풍)·처리시간.
   failed{reason} 분포로 원인 축을 좁혀라.
3. 개별 의심 건: MCP `refund_simulate` 로 기대값(환불 가능액/멱등키 요구/주문 상태 전이)을
   재계산해 실제와 대조하라. 쿠폰 적용 건은 `coupon_simulate(refundAmount)` 로 안분까지.

보고: 결론 한 줄 → 수치 병기(캡처/환불/실패) → 증거 → 권고 조치.
초과 환불·부분환불 멱등키 생략을 발견하면 severity 최상으로 보고하라.
DB 직접 수정은 어떤 경우에도 제안하지 마라.
