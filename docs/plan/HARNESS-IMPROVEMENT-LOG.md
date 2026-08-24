# 하네스 개선 로그

하네스(가드·스킬·훅·CI 게이트)를 **고칠 때마다 한 줄 남기는 곳**. 코드 변경 로그가 아니라
_판정 로그_ 다. 지금까지 하네스는 계속 늘어나기만 했고, 어느 개선이 실제로 효과가 있었는지
되짚을 근거가 없었다. 규칙은 한 번 들어오면 아무도 지우지 않는다 — 효과를 잰 적이 없으니
지울 근거도 없기 때문이다.

## 규칙

각 항목은 **반증 가능한 계약**으로 쓴다. 예측을 먼저 적고, 나중에 그 예측을 실제 관측과 대조한다.
예측이 틀렸으면 되돌린다. 되돌린 것도 지우지 말고 `reverted` 로 남긴다 — 실패한 시도의 기록이
같은 시도를 두 번 하지 않게 막는 유일한 장치다.

| 필드               | 뜻                                                                                |
| ------------------ | --------------------------------------------------------------------------------- |
| `status`           | `candidate`(효과 미검증) · `verified`(예측대로) · `reverted`(예측 빗나감, 되돌림) |
| `predicted_effect` | 무엇이 **관측 가능하게** 달라질 것인가. "품질이 좋아진다" 는 예측이 아니다.       |
| `verified_at`      | 예측을 실제 데이터와 대조한 날짜 + 근거. 안 했으면 `미검증` 이라고 정직히 쓴다.   |

검증 데이터 출처는 `node scripts/harness/telemetry-report.mjs` (가드 실행 분모·차단 통계·
스킬 사용률·라우터 순응률)와 `node scripts/harness/session-metrics.mjs` (완주율·재작업률).

⚠️ 분모 주의: `guard-hits.jsonl` 은 **위반만** 기록한다. 실행 이력(`guard-runs.jsonl`)이 없던
시절의 "차단 0회" 는 무위반이 아니라 미측정이다. 2026-08-12 이전 데이터로 효과를 주장하지 말 것.

---

## 항목

### 2026-08-20 · CI 판정 조회(ci-verdict.mjs) — 취소된 실행을 통과로 읽는 축 차단

- **status**: candidate
- **동기**: "가짜 GREEN" 의 5번째 경로. develop 의 ci·harness-guard 는 `cancel-in-progress` 라 연속 push
  중간 커밋의 실행이 `cancelled` 로 끝나는데, `cancelled` 는 `failure` 가 아니라 브랜치에도 `gh run list` 에도
  빨간 X 를 남기지 않는다 — **판정이 없는 것과 판정이 통과인 것이 같은 색**이다. 경로 필터가 겹치면 구멍이
  영구화된다: `Frontend - Tests` 는 `frontend == 'true'` 일 때만 돌고 push 의 변경 감지 기준은 직전 커밋이라,
  프론트를 바꾼 커밋의 실행이 취소되면 뒤 커밋들이 그 잡을 `skipped` 로 넘겨 그 변경은 영영 테스트되지 않는다.
  2026-08-19 실측(커밋 `1d17aaa7d`): ci 실행 32292943467 취소 → 잡 단위 재실행(attempt 2)마저 취소 →
  판정은 상시 열려 있던 릴리스 PR 실행 32299156814 에서 **우연히** 메워졌다. PR 이 닫혀 있었다면 미판정으로 남는다.
- **predicted_effect**: 커밋의 판정 유무를 눈이 아니라 종료 코드로 묻게 된다 — `success`/`failure` 만 결론으로
  세고 `cancelled`·`skipped`·진행중은 결론이 아니다. 판정을 대상 → 후손 → 조상+경로 무변경 순으로 찾아
  `PASS`/`COVERED`/`PENDING`/`UNJUDGED`/`FAIL` 로 가른다. 도입 시점 실측으로 위 2026-08-19 사례를 재현했다
  (`1d17aaa7d` → 6종 전부 후손 커밋 판정 = 자기 실행은 판정을 내지 못했다는 사실이 그대로 드러남).
- **드리프트 방어**: `test/ci-verdict-gate.test.mjs` 가 필수 체크 표(이름·경로 조건)를 `ci.yml`·`harness-guard.yml`·
  `semgrep.yml` 원문 및 CLAUDE.md 목록과 대조한다. 잡 이름이나 `if:` 만 바뀌면 도구는 **없는 체크를 조회해
  영원히 UNJUDGED** 를 뱉거나(오탐) 조상 판정을 잘못 유효화한다(미탐) — 둘 다 컴파일도 CI 도 못 잡는다.
  게이트가 실제로 무는지 확인함(scope 를 frontend→backend 로 바꾸면 FAIL, 되돌리면 20/20 PASS).
- **한계(명시)**: 읽기 전용이다 — 재실행은 사람이 한다(잡 단위 재실행은 경로 필터·concurrency 에 다시 걸리므로
  실행 전체를 다시 돌려야 하고, 그 판단은 자동화 대상이 아니다). CI 잡으로는 못 만든다(자기 실행을 판정할 수 없다) —
  릴리스 전·완료 보고 전 수동 조회가 사용 지점이다. 후손 판정을 유효로 세는 규칙은 "대상 커밋 자신은 안 돌았다" 를
  통과로 바꾸므로, 어디서 판정됐는지를 `PASS(descendant)` 로 항상 함께 출력한다.
- **도입 당일 자기 실증(2026-08-20)**: 도구가 자기 커밋을 `guard FAIL` 로 오보했다 — 후손 walk 에서 **가장 가까운**
  결론만 취해서, `70e4c9be3` 에서 깨졌다가 `44a8a5b8d` 에서 복구된 이력이 `4aeb4bf4b` 에 영구히 눌어붙었다
  (실패 원인은 남의 커밋인 배송정책 컨트롤러 미분류였다). 규칙을 고쳤다: 후손 중 **가장 나중** 판정이 그 내용의
  현재 진실이고, 대상 커밋 **자신의** 판정이 있으면 그것이 그 커밋의 사실이며(bisect·롤백이 이 값을 본다),
  도중에 뒤집힌 이력은 `· 중간 <sha> 에서 한 번 깨졌다 복구` / `· 이후 <sha> 에서 PASS 로 바뀜` 으로 함께 남긴다.
  유닛 3케이스 추가(복구·대상실패·뒤에서다시깨짐).
- **verified_at**: 미검증 (유닛 23케이스 + 라이브 3커밋 대조 PASS)

### 2026-08-19 · 커버리지 게이트 공전(측정 대상 0개)을 실패로 전환 — 정적+런타임 2겹

- **status**: verified
- **동기**: LINE 90% 게이트가 **아무것도 재지 않은 채 3개 모듈에서 초록**이었다. JaCoCo 검증은 대상
  클래스가 0개면 만들 위반이 없어 통과한다 — 커버리지가 높아서가 아니라 잰 게 없어서인데, 빌드는
  BUILD SUCCESSFUL 이고 리포트 파일도 생성되므로 사람이 XML 의 `<class>` 개수를 세기 전에는 보이지 않는다.
  원인은 `classDirectories` 이중 적용: 루트가 이미 교체한 값 위에 deposit·board(리포트+검증)·education(검증)이
  같은 관용구를 다시 얹었고, `classDirectories.files` 가 설정 시점에 즉시 평가되면서 `build/classes` 가 아직
  없는 클린 빌드(=CI 의 `clean :module:build`)에서 빈 집합이 스냅샷됐다. 산출물이 남은 로컬 빌드에서는
  0개가 되지 않는 대신 엔트리가 개별 `.class` 파일로 굳어(트리 루트가 파일) 모듈이 얹은 경로 제외가
  한 건도 매치되지 않았다 — 로컬 수치도 의도한 범위가 아니었다.
- **predicted_effect**: 측정 대상 소유권을 루트로 단일화 → 세 모듈이 실제로 측정된다. 이후 어떤 경로로든
  대상이 0개가 되면 빌드가 FAIL 하므로, "가짜 GREEN" 이 조용히 남지 않는다.
- **verified_at**: 2026-08-19 · 격리 워크트리(CI 조건: 산출물 없는 체크아웃 + `clean`)에서 대조
  - before: deposit·board 리포트 XML 245바이트/클래스 0개, HTML `No class files specified`, 게이트 통과.
    education 은 리포트 33개인데 검증 대상 0개. 대조군 organization-service 는 같은 실행에서 39개 측정(정상).
  - after: 측정 대상 deposit 58 / board 76 / education 18 클래스, LINE 실측 **0.97 / 0.92 / 0.91** 로
    기존 90% 게이트를 그대로 통과(임계값 1.00 상향 시 Gradle 이 정상 FAIL — 게이트 생존 확인).
  - 부정 검증: deposit 에 이중 적용을 일부러 되살리자 정적 게이트 FAIL(`deposit-service/build.gradle.kts:97,98`)
    + 클린 빌드에서 런타임 스모크 FAIL(`[coverage] :deposit-service:jacocoTestCoverageVerification … 0개`).
- **한계(명시)**: ① 이제 게이트 범위 안에 들어온 `board/adapter/out/storage`(10.0%)·`board/adapter/in/schedule`(16.7%)·
  `education/adapter/out/audit`(25.0%) 를 루트 제외 목록에 넣을지, 단위 테스트로 덮을지는 **미결**이다
  (board 의 0.92 는 이 둘을 안고 넘긴 값이다). ② 스모크는 "0개"만 막는다 — 대상이 1개로 쪼그라드는
  부분 축소는 여전히 못 잡는다.
- **적용**: `build.gradle.kts`, `shared-common/build.gradle.kts`(별도 빌드라 자체 선언),
  `deposit-service`·`board-service`·`education-service/build.gradle.kts`,
  `scripts/harness/test/coverage-scope-gate.test.mjs`, `scripts/harness/manifest.json`

### 2026-08-16 · CI 변경 감지 기준점 — push 는 직전 커밋 대비(전 모듈 실행 제거)

- **status**: verified
- **동기**: 경로 필터·모듈 매트릭스는 정교하게 짜여 있는데 **입력이 틀려서 무력화**돼 있었다.
  `dorny/paths-filter` 에 `base` 를 주지 않으면 기본 브랜치(main)와의 merge-base 를 기준으로 삼는데,
  main 이 develop 보다 1300 커밋 뒤처져 있어 develop push 는 무엇을 바꿨든 전부 바뀐 것으로 잡혔다.
  `shared` 필터(build.gradle.kts·Dockerfile·ci.yml)가 반드시 걸려 언제나 '전 서비스 재빌드' 분기.
- **predicted_effect**: push 는 직전 커밋 대비로 판정 → 바뀐 모듈만 실행. PR 은 base 를 비워
  main 대비 유지 → 머지 전 전량 검증은 그대로. 상시 열린 develop→main 릴리스 PR 이 안전망이라
  push 쪽을 줄여도 게이트가 약해지지 않는다.
- **verified_at**: 2026-08-16 · 같은 워크플로·같은 브랜치에서 기준점만 바뀐 두 실행을 대조
  - before (run 31892010325): `Searching for merge-base main...develop` → `Detected 274 changed files`
    → 백엔드 모듈 19잡 (문서 2개·tsx 2개만 바꾼 push)
  - after (run 31894160978): `변경 감지 기준: 직전 커밋 331f6238…` → **`Detected 2 changed files`**
    → `["backend","shared"]`. 이 실행이 18모듈을 돈 것은 바꾼 파일이 `ci.yml`(=shared) 이라 **의도대로**다.
- **한계(명시)**:
  - 폴백이 전량으로 떨어진다 — 최초 push·force-push 로 `before` 를 못 찾으면 base 를 비운다.
    판단이 갈릴 때 더 도는 쪽을 택한 것이고, 그래서 절감이 항상 보장되지는 않는다.
  - **문서만 바꾼 push 는 여전히 전 모듈이 돈다.** `backend` 필터가 `'**' + '!frontend/**'` 라 docs 도
    backend 로 잡히고, 매칭된 서비스가 0개면 '모호한 backend 변경 → 안전하게 전 모듈' 분기를 탄다.
    이건 별개의 (의도된) 페일세이프이므로 이번 변경 범위 밖에 뒀다 — 줄이려면 그 분기의 안전성을
    따로 판단해야 한다.
- **적용**: `ci.yml`, `polyglot-ci.yml`(같은 원인 — 당시 폴리글랏 7종 중 하나만 고쳐도 7개가 전부 돌았다)
### 2026-08-15 · 리포트 신선도 게이트(report-freshness.mjs) — 낡은 XML 인용 차단

- **status**: candidate
- **동기**: "가짜 GREEN 4경로" 중 'UP-TO-DATE 낡은 XML' — 직전 빌드 산출물을 이번 변경의 증거로
  인용하는 실수는 지금까지 운용 지식("인용 전 mtime 확인")으로만 막았다.
- **predicted_effect**: 게이트 결과 인용 전 `report-freshness <module>` 실행이 관례가 되면, 소스 수정 후
  재빌드 없이 "통과" 를 주장하는 보고가 STALE(exit 1)로 걸러진다. 리포트 부재(미실행)도 MISSING 으로 구분.
- **한계(명시)**: mtime 근사 — 소스 무변경 재실행은 fresh 로 본다(옳음), Docker 다운 skip 축은 별개 문제로 남는다.
- **verified_at**: 미검증 (유닛 6케이스 도입 시점 PASS)

### 2026-08-15 · CI 텔레메트리 로컬 합산(telemetry-ci-pull + --merge)

- **status**: candidate
- **동기**: 러너 실행 이력이 아티팩트로만 남아 로컬 리포트와 단절 — 규칙 효과 판정의 분모가 로컬
  체크아웃 하나로 좁았다(상태 관측의 머신 경계 단절).
- **predicted_effect**: `telemetry-ci-pull.mjs` 수집 + `--merge` 리포트에서 mode list(CI) 실행 분모가
  로컬 집계에 합산돼, 규칙별 발화/0회 판정이 CI 포함 전체 창으로 넓어진다.
- **verified_at**: 미검증 (병합·멱등 수집 유닛 4케이스 도입 시점 PASS — 실데이터 합산은 다음 리포트에서)

### 2026-08-15 · organization-domain-rules — 서비스 규칙 커버리지 16/16 완결

- **status**: candidate
- **동기**: 마지막 미커버 서비스. 발행 전용 경계·활성 OWNER ≥1·card 프로젝션 계약(드리프트 3종)은
  코드에 있지만 스킬·라우터가 안 실어 주는 지식이었다.
- **predicted_effect**: organization 경로 편집 시 라우터 주입이 발생하고, 커버리지 공백 섹션이 사라진다
  (16/16). 이후 신규 서비스 추가 시 "스킬+ROUTES 동시 배선" 관례의 기본값이 된다.
- **verified_at**: 미검증 (라우팅 2케이스 도입 시점 PASS)

### 2026-08-15 · Bash 명령 가드(COMMAND_RULES, --hook-bash) — 실시간 계층의 두 구멍 봉쇄

- **status**: candidate
- **동기**: ① 실시간 가드 매처가 `Write|Edit|MultiEdit` 뿐이라 sed -i·heredoc 리다이렉트로 소스를
  쓰면 내용 스캔을 통째로 우회했다(백슬래시 손실 사고 2회 전력). ② "운영 DB 명령 차단(check-command)"
  은 settlement-copilot **플러그인 소유**라 플러그인 미설치 환경(CI·새 클론·Codex)엔 아예 없었다 —
  HARNESS 의 "플러그인 독립" 전제와 모순.
- **predicted_effect**: telemetry `mode hook-bash` 실행 분모가 세션마다 기록되고, CMD-EDIT-BYPASS /
  CMD-NO-VERIFY 발화가 0회면 "완전 예방"(카나리아 PASS 로 생존 확인), 발화하면 실시간에서 잡힌
  우회 시도다. 소스 파일의 heredoc 손상 재발이 0 이 된다.
- **위험**: 오탐이 Bash 전체를 마찰시킨다 — 대상을 소스 확장자(.java/.kt/.sql/.mjs/.yml)로 좁히고
  fail-open + `HARNESS_ALLOW_CMD=1` 탈출구를 뒀다. 오탐 발견 시 규칙을 좁힌다(끄지 않는다).
- **verified_at**: 미검증 (카나리아 4종·유닛 12케이스는 도입 시점 PASS — 실전 발화는 2주 뒤 리포트로)

### 2026-08-15 · skill-router 세션 상태 GC (14일 보존)

- **status**: candidate
- **동기**: `.claude/harness/state/` 에 세션당 1개 상태 파일이 정리 정책 없이 누적(실측 ~70개).
  실해는 작지만 "상태 관리에 GC 가 없다"는 구조 결함.
- **predicted_effect**: 상태 파일 수가 14일 활동 세션 수로 수렴한다(무한 증가 중단). dedupe 동작은
  불변(신선한 세션 상태는 건드리지 않음 — 테스트 고정).
- **verified_at**: 미검증 (2주 뒤 `ls .claude/harness/state | wc -l` 로 대조)

### 2026-08-15 · insurance/deposit 도메인 규칙 스킬 + 라우터 배선 (커버리지 공백 해소)

- **status**: candidate
- **동기**: HARNESS.md 가 스스로 "우선 부채"로 명시한 돈 경로 2서비스(보험 수수료정산·25%룰·완전판매
  게이트 / 예치금 hold·offset 이중사용 차단)가 전용 `*-rules` 스킬·라우터 행 없이 방치.
- **predicted_effect**: insurance/deposit 경로 편집 시 라우터가 해당 스킬을 주입하고(순응률 지표에
  등장), 두 서비스의 도메인 규칙 위반(만료 회수 originalAmount·referenceType 변경·게이트 후퇴 등)이
  리뷰에서 스킬 근거로 지적된다. 커버리지 공백 섹션은 organization 1개로 축소.
- **verified_at**: 미검증 (skill-router.test.mjs 라우팅 3케이스는 도입 시점 PASS)

### 2026-08-12 · 가드 실행 분모(guard-runs.jsonl) 추가

- **status**: candidate
- **동기**: 9개 체크아웃 어디에도 `../../.claude/harness/logs` 가 없었다. 이게 "아무도 규칙을 어기지
  않았다" 인지 "훅이 안 돌았다" 인지 구분할 방법이 없었다. 위반만 적는 로그의 구조적 한계.
- **predicted_effect**: 다음 리포트부터 `mode hook: N회 실행` 이 0 이 아니게 찍힌다. 0 이면
  PreToolUse 훅 배선이 죽어 있다는 뜻이므로 그때는 훅부터 고친다.
- **verified_at**: 부분 — 2026-08-12 `verify.sh` 1회 실행만으로 `mode hook: 3회 · mode list: 2회`
  가 기록되어 배선 자체는 확인됐다. 다만 예측의 본체(실제 에이전트 세션에서 0 이 아님)는
  병합 후 2주 뒤 `telemetry-report.mjs` 로 다시 본다.

### 2026-08-12 · scripts/verify.sh — 로컬에서 CI 판정 재현

- **status**: candidate
- **동기**: CI 가 하는 판정을 로컬에서 같은 순서로 돌릴 방법이 없었다. 그 결과 하네스 테스트가
  개발자 맥에서 3건 깨진 채 방치돼 있었다(모두 macOS `/var`→`/private/var` 심링크 문제로,
  리눅스 CI 에서는 통과해 보이지 않았다). 아무도 로컬에서 안 돌렸다는 증거.
- **predicted_effect**: "다 됐다" 자기보고 대신 종료 코드로 증명. CI 에서 처음 빨간불이 뜨는
  일이 줄어든다.
- **위험**: 느려지면 우회당한다. 기본 경로는 변경 모듈만 빌드하고, 수초짜리 하네스 게이트를
  수분짜리 Gradle 앞에 둔다.
- **verified_at**: 부분 — 2026-08-12 작성 당일 이미 3건을 잡았다(guard.test.mjs 2건 ·
  install.test.mjs 1건, 전부 macOS 심링크). 수정 후 154 테스트 통과·감사 healthy·가드 clean 으로
  exit 0. "CI 첫 빨간불이 줄어든다" 는 추세 예측이라 여전히 미검증.

### 2026-08-12 · CI 텔레메트리 아티팩트 업로드

- **status**: candidate
- **동기**: 러너는 매 실행 폐기되고 로그 디렉토리는 gitignore 대상이라, CI 쪽 가드 실행 이력이
  전량 소실되고 있었다.
- **predicted_effect**: PR 마다 `harness-telemetry-*` 아티팩트가 남아, 규칙별 발화 빈도를
  30일 창으로 되짚을 수 있다.
- **verified_at**: 미검증

### 2026-07-22 · 가드 카나리아 + 라우터 순응률 + 세션 메트릭 (29be679d)

- **status**: verified (부분)
- **predicted_effect**: 규칙별 차단 0회가 "죽은 규칙" 인지 "완전 예방" 인지 판별 가능해진다.
- **verified_at**: 2026-08-12 — 9개 규칙 카나리아 전부 PASS. `inflearn/test/telemetry-report.test.mjs`
  의 `every rule has a canary fixture and every canary passes` 가 CI 게이트로 강제되므로
  규칙 사망은 기계적으로 차단된다. **단** 순응률·세션 메트릭 쪽은 입력 데이터가 0건이라
  여전히 미검증이다.

### 2026-07-24 / 2026-07-25 · MSA-BOUNDARY 를 inverse-allowlist 로 전환 (aaf9f962, b92b3a84)

- **status**: verified
- **predicted_effect**: denylist 나열에서 빠진 신규 order 도메인 import 가 더 이상 통과하지
  못한다. `import static` 우회도 막힌다.
- **verified_at**: 2026-08-12 — `guard.test.mjs` 가 payment·review·game·category·menu·rbac·
  order·user 차단과 settlement 자기 컨텍스트 12개 허용을 양방향으로 고정. false positive 회귀도
  같은 테스트가 잡는다.

### 2026-08-07 · 하네스 경로 삭제 가드 (7672b48e, 0c16b896)

- **status**: verified
- **동기**: PR #210 에 섞여 `.claude`/`.codex`/`../harness` 270 파일이 조용히 지워진 사고.
- **predicted_effect**: 하네스 보호 경로 삭제가 PR 단계에서 차단된다.
- **verified_at**: 2026-08-12 — `--deleted-list` 모드가 CI 에 배선돼 있고, 2026-08-12 부터
  `../../scripts/verify.sh` 도 같은 검사를 로컬에서 돈다.

### 2026-08-15 · 문서 사실 게이트에 "서비스 수" 규칙 추가 + 부사 삽입형 소비처 주장 포착

- **status**: verified
- **동기**: HARNESS.md 가 3주간 `14 마이크로서비스` 로 남아 있었다(실제 16). 같은 문서 안에
  `자바 16서비스` 줄이 공존해 **자기모순**이었는데도 `harness-audit` 는 healthy 였다 — 모듈 로스터
  대조가 트리 표기만 보고 산문 주장은 안 봤기 때문. 같은 점검에서 `소비처가 아직 미배선`(organization)
  이 gate #3 을 통과한 것도 드러났다. card-service 가 4토픽을 실제 소비 중인데, 정규식이
  `소비처(가) 미배선` 만 보고 사이에 낀 `아직` 을 못 넘었다.
- **predicted_effect**: 상태 기술 문서에서 서비스 수를 안 고치면 audit FAIL → CI 차단.
  로스터 앵커(gateway·DB-per-service)가 같은 줄에 있을 때만 주장으로 인정하므로
  부분집합("금융 5서비스")·폴리글랏 합계(24)는 오탐하지 않는다.
- **verified_at**: 2026-08-15 — 규칙 투입 직후 실제 저장소에서 `HARNESS.md:67` 1건을 잡았고(수정 후
  healthy 복귀), 상태 기술 문서 8종 전수에서 오탐 0건. `audit.test.mjs` 가 "잡는다/오탐 안 한다"
  5쌍으로 고정.

### 2026-08-16 · SonarCloud 신규코드 기준선을 릴리스 시점에 고정 (설정 API 는 함정)

- **status**: verified
- **동기**: 릴리스 PR #263 에서 필수 CI 6종은 전부 초록인데 `SonarCloud Code Analysis` 만 빨간불이었다.
  파고 보니 신규코드 창이 **30일 롤링**이라 42,538 라인이 누적돼 있었고, 그 안에서
  `new_coverage 58.2% < 80%` · `new_reliability_rating C < A` 로 떨어졌다. 즉 게이트가 "방금 쓴 코드"가
  아니라 "지난 한 달 전부"를 판정하고 있었다 — 어느 커밋 탓인지 지목할 수 없으니 아무도 고치지 않는다.
  커버리지 격차는 JaCoCo 게이트가 어댑터를 제외하는 반면 Sonar 는 포함해 세는 **분모 차이**이기도 하다.
- **한 일**: `POST api/project_analyses/set_baseline`(project·branch·analysis UUID)로 develop 의
  기준선을 릴리스 직후 분석(2026-08-15T17:17:56Z)에 고정했다.
- **⚠️ 빠졌던 함정 (같은 시도를 두 번 하지 않게 남긴다)**:
  - `POST api/settings/set` 으로 `sonar.leak.period(.type)` 를 바꾸면 **HTTP 204 가 오고 읽기 확인도
    통과하지만 분석은 그 값을 읽지 않는다**. 이 키는 정의가 없어 설정 API 가 검증 없이 아무 문자열이나
    저장한다 — 무의미한 `value=reference_branch` 도 204 로 받았다. 새 분석을 2회 돌려도
    `periods[].mode` 가 계속 `days/30` 이었던 것이 반증이다.
  - `api/new_code_periods/*` 는 SonarCloud 에 **없다**(404 Unknown url). `api.sonarcloud.io` v2 는 Forbidden.
    가용 엔드포인트는 추측하지 말고 **`api/webservices/list` 를 grep** 하는 게 정본이다.
  - "reference branch = main" 은 **원천 불가** — 이 조직 플랜은
    `Organization is not allowed to access data from non main branches.` 로 비주 브랜치 데이터를 막는다.
    게다가 Sonar 의 주 브랜치는 `main` 이 아니라 `develop` 이다(main push 의 분석은 성공해도 조회 불가).
- **predicted_effect**: 다음 develop 분석부터 신규코드가 릴리스 이후 델타로만 잡혀, 게이트 실패가
  "누가 언제 넣은 것인지" 지목 가능한 크기가 된다.
- **verified_at**: 2026-08-16 — 기준선 고정 후 실제 재분석에서 `periods[].mode=manual_baseline`
  (date 2026-08-15T17:17:56Z), `new_lines 42,538 → 0`, 품질 게이트 `ERROR → OK`.
  **설정 읽기 확인이 아니라 새 분석 후의 `periods[].mode` 만이 증거다.**
- **남은 부채**: ① 기준선은 자동 갱신되지 않는다 — **릴리스마다 `set_baseline` 을 다시 찍어야 한다**
  (안 찍으면 창이 다시 무한정 자란다). ② 이번에 창 밖으로 빠진 신규코드 신뢰성 MEDIUM 12건
  (`java:S8786` 정규식 백트래킹 7 · `java:S6218` byte[] record equals 3 · `java:S6829` 생성자
  `@Autowired` 2)은 **사라진 게 아니라 판정 대상에서 빠졌을 뿐**이다. ③ main push 의 Sonar 분석은
  플랜상 조회 불가라 4분을 버리는 낭비다. ④ **고정한 분석이 보관주기로 삭제되면 기준선이 끊긴다**
  — 2026-08-18 에 실제로 터졌다(아래 항목).

### 2026-08-18 · 수동 기준선이 보관주기로 끊겨 전 분석이 422 (재발 조건 확정)

- **status**: verified
- **동기**: develop CI 의 `Backend - Build/Test/JaCoCo/SonarCloud` 만 빨간불이었다. 모듈 테스트 18종은
  전부 초록인데 `:sonar` 만 죽었다. 위 2026-08-16 항목에서 고정한 기준선 분석이 **SonarCloud 보관주기로
  삭제**되어 있었다.

  ```
  HttpException: Error 422 on https://api.sonarcloud.io/analysis/analyses
  {"msg":"Analysis '3ba45d91-…' configured as manual baseline for the New Code Period
   no longer exists. Please update the New Code Period configuration."}
  ```

- **재발 조건(정본)**: `set_baseline` 으로 고정한 분석은 **영구 보존되지 않는다**. 그 분석이 지워지면
  품질게이트가 미달로 뜨는 게 아니라 **분석 생성 자체가 422 로 거부**된다 — 즉 커버리지·이슈 로그가
  아예 남지 않고, 릴리스마다 다시 찍지 않으면 어느 날 갑자기 CI 가 멈춘다. 실측: 2026-08-18 기준
  develop 에 남은 분석은 6개뿐이었고 고정해둔 2026-08-15T17:17:56Z 은 이미 없었다
  (가장 가까운 잔존분은 2026-08-15T05:35:17Z).
- **한 일**: `POST api/project_analyses/set_baseline` 로 잔존 분석 중 최신
  (`a7808667`, 2026-08-18T09:49:36Z, rev `49af8656`)에 재고정(HTTP 200).
- **⚠️ 오진 함정**: ① 실패 화면이 "테스트 실패"처럼 보인다 — 잡 이름에 Test 가 들어 있고, 같은 시각
  concurrency 로 취소된 잡들이 섞여 있으면 더 헷갈린다. 실패 로그에서 `HttpException: Error 4xx` 를
  먼저 찾을 것. ② 로그의 `digest-mismatch: error` 와 `::error::백엔드 모듈 테스트가…` 는 워크플로
  스크립트의 **에코일 뿐** 실패가 아니다.
- **predicted_effect**: 재고정 이후 `:sonar` 가 다시 성공하고, 신규코드 창은 재고정 시점 이후 델타로만
  잡힌다.
- **verified_at**: 2026-08-18 — 재고정 후 Sonar 잡 재실행 2건이 모두 success(커밋 `1770c956` 13:27→13:34,
  `3fac8390` 동일). 증거는 설정 읽기가 아니라 새 분석의 `periods[].mode`:
  `[{"index":1,"mode":"manual_baseline","date":"2026-08-18T09:49:36+0000"}]`.
- **점검 명령**(끊겼는지 1분 안에 확인):

  ```bash
  TOKEN=$(grep -m1 '^SONAR_TOKEN=' .env | cut -d= -f2-)
  curl -s -u "$TOKEN:" "https://sonarcloud.io/api/project_analyses/search?project=MyoungSoo7_settlement&branch=develop&ps=5"
  # manualNewCodePeriodBaseline:true 인 분석이 하나도 없으면 끊긴 것이다
  ```

- **남은 부채**: ① 재고정은 여전히 수동이고, **끊긴 사실을 CI 가 알려주지 않는다**(분석이 죽어야 드러난다).
  점검 명령은 [릴리스 런북](runbook/release-develop-to-main.md) ①·④ 단계에 붙였다 — 자동 감지는 미해결. ② 재고정 직후 실측
  `new_lines 6,329 · new_coverage 77.4% (< 80%) · new_reliability_rating 1.0(A)` — 잡은 통과해도
  **품질게이트 자체는 커버리지로 미달**이라 main PR 에서 걸린다.

---

## 측정된 것 (2026-08-15 갱신 — 이전 "못 재는 것" 3항목이 전부 데이터를 갖게 됨)

재현: `node scripts/harness/telemetry-report.mjs` · `node scripts/harness/session-metrics.mjs`

- **위반 시도 빈도** — 가드 실행 1563회(분모: hook 1341 · staged 205 · files 9 · list 8) 대비
  **차단 11건(0.7%)**, 최근 14일 6건. 최다 `MONEY-PRIMITIVE` 5 · `MSA-BOUNDARY` 4.
  0회 규칙 7종은 카나리아가 전부 PASS 하므로 "죽은 규칙"이 아니라 **완전 예방**으로 판정된다.
- **스킬 로드** — `skill-usage.jsonl` **295회**. 상위 `tdd-discipline` 55 · `settlement-domain-rules` 28 ·
  `verify-before-done` 25 · `idempotency-and-events` 22 · `ledger-invariants` 21.
  라우터 순응률(제안→로드) **100% (197/197)** — 목표 ≥80% 대비 초과 달성.
- **상주/온디맨드 비중** — 상주 CLAUDE.md 20.1KB vs 온디맨드 37스킬 183.7KB → **상주 비중 10%**.
- **완주율·재작업률(KPI-3/4)** — KPI-3 완주율 100%(2/2)이나 **n<10 이라 추이 지표로만** 쓴다.
  KPI-4 재작업률 최근 30일 **21.6%(183/849)** — 베이스라인 19.3%(2026-07-22) 대비 **상승**했다.
  하향이 목표였으므로 이 항목은 아직 개선 실패로 읽는 것이 정직하다.

## 아직 못 재는 것 (정직한 공백)

- **스킬 37개 중 값을 하는 것** — 로드 빈도는 이제 알지만 **로드가 결과를 바꿨는지**는 모른다.
  로드 0회 13종(`compliance-review` · `economics-data-rules` · `hookify-to-guard` · `incident-runbooks` ·
  `market-quotes-rules` · `oo-score` · `operation-signal-rules` · `recon-playbook` · 인터뷰 서브하네스
  `socrates`·`wonder`·`reflect`·`refine`·`restate`)도 "안 쓰임"과 "해당 상황이 안 옴"이 구분되지 않는다
  — 가드의 카나리아에 해당하는 장치가 스킬 쪽엔 없다.
- **KPI-4 상승의 원인** — 재작업률이 올랐다는 사실은 재지만, 하네스 탓인지 작업 성격(대규모 캠페인
  다수) 탓인지 분해할 축이 없다.
