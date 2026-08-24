---
description: fashion-copilot 시작 가이드 — 커맨드·MCP 도구·skill 카탈로그와 상황별 진입점 안내
argument-hint: "[궁금한 주제, 생략 시 전체 카탈로그]"
---

fashion-copilot 플러그인의 사용법을 안내하라. 질문: $ARGUMENTS (비어 있으면 전체 카탈로그 요약).

아래 카탈로그를 기준으로, 사용자의 상황에 맞는 진입점(커맨드 → 도구 → skill 순)을 추천하라.

## 커맨드 (상황별 진입점)

| 커맨드 | 언제 | 하는 일 |
|---|---|---|
| `/drop-check [before\|live]` | 한정판 드랍 전/중 | 재고 차감 거절율 해석 + 드랍 체크리스트 판정 |
| `/return-audit [date]` | 반품·환불 이상 의심 | 일일 환불 대사 + 실패율/멱등 재사용 + 개별 건 재계산 |
| `/coupon-audit [대상]` | 쿠폰 코드 수정·검증 | 할인 계산 교차검증 + 초과 사용 방어 점검 |
| `/review-scan [상품ID]` | 리뷰 어뷰징 의심 | 코드 무결성 점검 + 어뷰징 패턴 조사 절차 |

## MCP 도구 (fashion-copilot 서버, 전부 읽기 전용)

- `refund_recon(date)` — 일자별 캡처 vs 환불 금액·건수 (order 내부 대사 API)
- `refund_health()` — 환불 실패율·멱등키 재사용·처리시간 (Prometheus)
- `stock_pulse()` — 재고 차감 성공/거절 비율 (Prometheus)
- `coupon_simulate(...)` — 할인 FLOOR 절사·클램프·환불 안분 dry-run (오프라인)
- `refund_simulate(...)` — 환불 가능액·전액/부분·멱등키 판정 dry-run (오프라인)

`refund_recon` 은 `INTERNAL_API_KEY`, `refund_health`/`stock_pulse` 는 order-service 기동이 필요.
simulate 2종은 네트워크 없이 항상 동작한다.

## Skills (코드 작성·리뷰 시 자동 참조)

`size-return-policy`(환불 3-Phase·멱등키), `drop-stock-integrity`(원자적 재고 차감),
`review-integrity`(1인1리뷰·구매검증 공백), `coupon-money-rules`(FLOOR·클램프·안분)

## 설치·진단이 필요하면

- 진단: `node fashion-copilot/install.mjs doctor`
- Codex 재동기화: `node fashion-copilot/install.mjs codex`
- 검증: `node fashion-copilot/install.mjs smoke`

사용자가 특정 주제를 물었으면 해당 부분만 깊이 있게, 관련 skill 파일 경로
(`../skills/*/SKILL.md`)와 함께 안내하라.
