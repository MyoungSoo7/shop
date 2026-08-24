---
description: 한정판 드랍 전/중 재고 정합성 점검 — 차감 거절율 해석 + 체크리스트 판정
argument-hint: "[phase: before|live, 생략 시 before]"
---

`drop-stock-integrity` skill 을 로드하고(skill 미지원 환경이면
`..stock-integrity/SKILL.md` 를 직접 읽어라), 드랍 점검을 수행하라.
대상 단계: $ARGUMENTS (비어 있으면 before — 드랍 전 점검).

1. MCP `stock_pulse()` — variant/product 차감 성공·거절 카운터와 거절율 확보
2. **before**: skill 의 드랍 전 체크리스트 4항목을 순서대로 판정하라
   (재고·status 확정 / 노출 캐시 / 베이스라인 채집 / stock.depleted 컨슈머).
3. **live**: 거절율을 skill 의 판정 기준으로 해석하라 —
   드랍 중 rejected 급증 = 품절 러시(정상), 평시 rejected 지속 = 재고 노출 버그 의심.
   버그 의심 시 `decreaseStockIfAvailable` 의 affected==0 분류 로직과 노출 캐시 TTL 을 코드에서 확인하라.

보고: 결론 한 줄(정상/이상) → 수치(성공/거절 카운터) → 근거 → 권고 조치.
read-modify-write 재고 코드를 수정안으로 제안하지 마라 — 조건부 UPDATE 경로만.
