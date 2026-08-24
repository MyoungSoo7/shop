---
description: 브랜치 diff 를 위험축으로 트리아지해 리뷰 순서를 정하고 P0부터 검토 (헥사고날 + MSA 기준)
argument-hint: "[base 브랜치, 생략 시 origin/develop]"
---

`delta-review` skill 을 로드하라. base = `$ARGUMENTS` (비어 있으면 `origin/develop`).

## 1) 델타 수집 — 읽기 전에 지형부터 본다

```bash
git diff --stat $ARGUMENTS...HEAD
git diff --name-status $ARGUMENTS...HEAD
mkdir -p .claude/harness && git diff --name-only --diff-filter=d $ARGUMENTS...HEAD > .claude/harness/delta-files.txt   # 삭제 파일 제외(가드가 실파일을 연다)
node scripts/harness/guard.mjs --list .claude/harness/delta-files.txt   # 경로는 저장소 상대만 허용(gitignore 영역)
```

가드 결과는 **재확인 대상이 아니라 전제**다(스킬 §0) — 위반이 있으면 그것부터 보고하고, 없으면 기계 축은 건너뛴다.

## 2) 트리아지

스킬 §1 표로 변경 파일을 **P0/P1/P2 + 축(A~K)** 으로 분류하고, 분류 결과를 먼저 출력한다
(파일 수·등급별 분포·적용 축). 이 표가 리뷰 순서이자 커버리지 근거다.

## 3) 순서대로 읽기

스킬 §2 를 따른다 — 세로는 `domain → port → application/service → adapter`,
가로는 프로듀서·계약·컨슈머를 **동시에** 연다. 3자 중 델타에 빠진 쪽이 있으면 먼저 그 결손을 판정한다.
P0 축을 전부 끝내기 전에 P1 로 내려가지 않는다.

## 4) 축별 검토

P0 는 스킬 §3(A 돈·원장 / B tx·Outbox / C 멱등 / D 인가·IDOR / E 마이그레이션),
P1 은 §4(F 계약 의미 / G 프로젝션 / H 포트 경계 / I 상태머신 / J 배선), 남는 예산은 §5.
도메인 판정 근거는 해당 `{서비스}-rules` 스킬에서 인용한다(기억으로 단정 금지).

## 5) 보고

스킬 §6 형식 그대로. 실패 시나리오를 못 쓰는 항목은 `[관찰]` 로 낮추고,
**발견 0건이어도 검사 범위(파일 수·적용 축·미적용 축과 이유)를 반드시 출력**한다.
마지막에 게이트 실행 여부(test/jacoco/archunit/계약테스트)를 사실대로 적는다 — 안 돌렸으면 "미실행"이라고 쓴다.
