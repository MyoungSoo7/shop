# 릴리스 런북 — develop → main

장애 대응이 아니라 **정기 릴리스 절차**다. 다른 런북과 달리 연결 알림이 없고, 사람이 시작한다.

## 전제 (main 은 보호 브랜치)

- PR 필수(승인 0인 · 스레드 해소 필수) · **squash 만** 허용 · deletion · non-fast-forward 금지.
- **필수 CI 6종**: `Detect changed paths` · `Backend - Build/Test/JaCoCo/SonarCloud` ·
  `Frontend - Production Build & Quality` · `Frontend - Tests` · `guard`(harness-guard) ·
  `SAST (Semgrep OSS)`.
- `polyglot-ci` 는 워크플로 수준 `on.paths` 필터라 해당 경로 미변경 PR 에서 체크가 **아예 보고되지
  않는다**(영구 대기). 그래서 필수에서 제외한다 — 정본은 [`CLAUDE.md`](../../../CLAUDE.md) 작업 프로토콜 절.

## 절차

### ① 릴리스 전 — SonarCloud 신규코드 기준선이 살아 있는지 확인

`set_baseline` 으로 고정한 분석은 **영구 보존이 아니다**. 보관주기로 지워지면 품질게이트가 미달로
뜨는 게 아니라 **분석 생성 자체가 422 로 거부**되어, 필수 CI 인 `Backend - Build/Test/JaCoCo/SonarCloud`
가 통째로 죽는다. 릴리스 PR 을 올린 뒤에 발견하면 그때부터 막힌다.

```bash
TOKEN=$(grep -m1 '^SONAR_TOKEN=' .env | cut -d= -f2-)
curl -s -u "$TOKEN:" "https://sonarcloud.io/api/project_analyses/search?project=MyoungSoo7_settlement&branch=develop&ps=5"
# manualNewCodePeriodBaseline:true 인 분석이 하나도 없으면 끊긴 것이다 → ④ 를 지금 먼저 수행
```

끊겼을 때의 증상과 오진 함정은
[HARNESS-IMPROVEMENT-LOG.md 2026-08-18 항목](../HARNESS-IMPROVEMENT-LOG.md)에 정본이 있다.

### ② develop 최신 커밋의 CI 가 **완주**했는지 확인

```bash
gh run list --branch develop --limit 5 --json headSha,workflowName,status,conclusion
```

`cancelled` 는 통과가 아니라 **미판정**이다. 연속 푸시가 있으면 concurrency 로 앞 런이 잘리므로,
릴리스 대상 커밋에 `conclusion=success` 인 ci 런이 실제로 있는지 본다.

### ③ 릴리스 PR 생성 → squash 병합

base `main`, head `develop`. 필수 CI 6종이 전부 초록인지 확인하고 squash 로 병합한다.

### ④ 릴리스 직후 — 기준선을 릴리스 시점으로 재고정

기준선은 자동 갱신되지 않는다. 안 찍으면 신규코드 창이 다시 무한정 자라 게이트가
"방금 쓴 코드"가 아니라 "지난 한 달 전부"를 판정한다.

```bash
TOKEN=$(grep -m1 '^SONAR_TOKEN=' .env | cut -d= -f2-)
# 1) 릴리스 직후 develop 분석의 key 확인
curl -s -u "$TOKEN:" "https://sonarcloud.io/api/project_analyses/search?project=MyoungSoo7_settlement&branch=develop&ps=5"
# 2) 그 분석에 고정
curl -s -u "$TOKEN:" -X POST "https://sonarcloud.io/api/project_analyses/set_baseline" \
  -d "project=MyoungSoo7_settlement" -d "branch=develop" -d "analysis=<ANALYSIS_KEY>"
# 3) 검증 — 설정 읽기가 아니라 새 분석의 periods[].mode 만이 증거다
curl -s -u "$TOKEN:" "https://sonarcloud.io/api/measures/component?component=MyoungSoo7_settlement&branch=develop&metricKeys=new_lines,new_coverage&additionalFields=periods"
# periods[].mode == "manual_baseline" 이어야 한다
```

`POST api/settings/set` 의 `sonar.leak.period` 는 **204 를 주고도 분석이 읽지 않는다**(무효).
`api/new_code_periods/*` 는 SonarCloud 에 없다(404). reference branch = main 도 이 조직 플랜에서는
불가 — 경위는 개선로그 2026-08-16 항목 참조.

### ⑤ back-merge — origin/main → develop

squash 병합은 main 에 새 커밋을 만들므로 develop 과 이력이 갈린다. 콘텐츠가 동일하므로
`-s ours` 로 이력만 정렬한다(실제 사례: `b130564fd`, `f13a85851`, `bd0fbcb19`).

## 함정

- **`cancelled` 를 초록으로 읽지 말 것** — 병행 세션이 푸시하는 동안은 ci 가 매번 잘려 완주 런이
  나오지 않는다. 릴리스 대상 커밋 하나에 대해 성공 런을 확인해야 한다.
- **Sonar 실패는 테스트 실패처럼 보인다** — 잡 이름에 Test 가 들어 있다. 실패 로그에서
  `HttpException: Error 4xx` 를 먼저 찾는다. `digest-mismatch: error` 와
  `::error::백엔드 모듈 테스트가…` 는 워크플로 스크립트의 에코일 뿐 실패가 아니다.
