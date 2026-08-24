---
name: hookify-to-guard
description: hookify 로 캡처한 훅 규칙(.claude/hookify.*.local.md)을 분류해 저장소 불변식은 guard.mjs 3중 강제(실시간·pre-commit·CI)로 이식하는 절차. hookify 규칙을 만들거나 수정한 직후, "훅 굳혀줘/이식해줘/guard 로 옮겨" 요청 시, 또는 hookify 규칙이 실전에서 유효 발화를 반복할 때 로드.
---

# Hookify → guard.mjs 이식 (규칙 정본은 한 곳이다)

## 철칙

**hookify 는 캡처 계층, guard.mjs 가 정본이다.** hookify 규칙(`.claude/hookify.*.local.md`)은
로컬 세션에서만 발화하고 pre-commit·CI 를 통과하지 못한다 — fresh clone·`--no-verify`·CI 에서는
아무것도 막지 못한다. 저장소가 지켜야 할 불변식이 됐다면 guard.mjs `RULES` 로 이식하고 hookify
원본은 **삭제**한다(이중 발화·정본 이원화 방지). 이식이 끝나기 전까지는 hookify 규칙이 임시
가드로 유효하다 — 이식 전에 먼저 지우지 않는다.

## 1단계 — 분류 (모든 hookify 규칙이 이식 대상은 아니다)

| 규칙 성격 | 행선지 |
|---|---|
| 돈·회계·MSA 경계·보안·OO 불변식 — 모든 클론과 CI 에서 강제돼야 함 | **guard.mjs 이식** (이 스킬 2단계) |
| `event: file` 이지만 아직 오탐 검증이 안 끝난 신규 규칙 | hookify 유지 — 발화 이력이 쌓인 뒤 재분류 |
| 개인 습관 넛지·세션 한정 실험 | hookify 유지 (`.local.md` 그대로) |
| `event: bash` (명령 차단) | guard 는 파일 스캔 전용(settings.json 훅 matcher `Write\|Edit\|MultiEdit`) — Bash 까지 강제하려면 settings.json 에 별도 PreToolUse(Bash) 훅 설계 필요. 아니면 hookify 유지 |
| `event: stop`/`prompt` (절차 리마인더) | guard 대상 아님. 강제가 아니라 권장이면 `skill-router.mjs` ROUTES(권장의 기계화)로 |

이식 판단 기준: **라인 단위 정규식으로 판정 가능한가?** guard 는 파일을 라인별 스캔한다 —
멀티라인 문맥이 필요한 규칙은 `scripts/harness/test/oo-gate.test.mjs` 스타일 전수 테스트로 간다.

## 2단계 — 이식 절차 (TDD 순서 고정)

1. **케이스 먼저**: `scripts/harness/test/guard.test.mjs` 의 `cases` 배열에
   `{ id, file, violation, normal }` 추가 → `node --test scripts/harness/test/guard.test.mjs`
   로 **실패를 목격**한다 (📘`tdd-discipline`).
2. **규칙 추가**: `scripts/harness/guard.mjs` 의 `RULES` 에 `{ id, when, test, msg }` 엔트리.
   - `id`: 대문자 케밥 `SCOPE-NAME` (예: `MONEY-PRIMITIVE`, `OO-DOMAIN-SETTER`).
   - `when`: 파일 경로 술어 — 기존 헬퍼(`isCore`/`isProd`/`isDomainMain`/`MONEY_SCOPE`) 재사용,
     테스트 소스 제외 여부를 의도적으로 결정한다(오탐 제로가 게이트 신뢰의 전제).
   - `msg`: 한국어 + 대안 제시 + 근거(스킬/ADR) 병기 — 기존 msg 형식을 따른다.
3. **정규식 번역** (hookify=Python `re` → guard=JS):
   - 명명 그룹 `(?P<x>)` → `(?<x>)`, 인라인 플래그 `(?i)` → `/i` 리터럴 플래그.
   - hookify 는 `new_text` 전체 매칭, guard 는 **라인 단위** — `^`/`$` 의미가 달라진다.
4. **green 확인 + 감사**:
   ```bash
   node --test scripts/harness/test/
   node scripts/harness/harness-audit.mjs
   ```
5. **원본 제거**: 이식된 `.claude/hookify.{이름}.local.md` 를 삭제한다.
   `enabled: false` 로 남기지 않는다 — 죽은 규칙 파일은 정본이 두 곳이라는 착각을 만든다.
6. **문서·커밋**: HARNESS.md 하드스톱 절에 규칙이 열거돼 있으면 갱신. 개별 커밋
   (📘`verify-before-done` 로드 후 완료 선언).

## 예외 통로

이식된 규칙도 guard 의 allowance 형식을 따른다 — 정당한 예외는 구조화 마커
(`harness-guard:` 접두 뒤 `allow` + `reason=`/`issue=`/`owner=`/`expires=YYYY-MM-DD` 필드,
정확한 문법은 guard.mjs `ALLOWANCE` 정규식이 정본)로만 통과한다. hookify 시절처럼
"규칙을 끄는" 우회는 없다.

## Red Flags

| 합리화 | 실제 |
|---|---|
| "hookify 에도 두고 guard 에도 두자" | 같은 위반이 두 번 발화하고, 규칙 수정 시 한쪽만 고쳐진다 — 정본은 한 곳 |
| "일단 RULES 에 넣고 테스트는 나중에" | `cases` 미등록 규칙은 회귀를 못 막는다 — 케이스가 먼저다 |
| "세션에서 잘 막았으니 이식 없이 충분" | hookify 는 이 머신의 이 세션만 막는다 — CI·fresh clone 은 무방비 |
| "bash 규칙도 대충 file 규칙으로 변환" | 이벤트 모델이 다르다 — 1단계 표대로 행선지를 다시 정한다 |
