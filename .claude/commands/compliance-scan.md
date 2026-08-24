---
description: 현재 브랜치 diff 를 금융 컴플라이언스 관점(PII/이력/감사/권한)으로 스크리닝
argument-hint: "[base 브랜치, 생략 시 origin/develop]"
---

`compliance-review` skill 을 로드하라. 대상: `git diff $ARGUMENTS...HEAD`
(인자가 비어 있으면 `origin/develop...HEAD`), 신규 파일 포함.

skill 의 4개 관점을 전부 검사하라:

1. 민감정보 — 로그/예외/이벤트/픽스처의 평문 PII
2. 이력 보존 — 정산·원장·지급 UPDATE/DELETE, 스냅샷 컬럼 setter, 과거 Flyway 파일 수정
3. 감사 추적 — 운영자 행위 API 의 조작자 식별·사유 필수 여부
4. 권한 — /admin 노출, /internal gateway 라우팅 추가, actuator 인증 해제

보고는 skill 의 형식([BLOCK]/[WARN] + 파일:라인 + 근거, 마지막에 통과한 관점 목록)을 따르고,
발견 0건이어도 "검사한 관점과 파일 수"를 명시해 침묵-통과처럼 보이지 않게 하라.
