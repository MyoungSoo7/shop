# HARNESS — Shop

> 개발 하네스 구성 — 헥사고날 + 커머스/운영 도메인 전용 에이전트·스킬·커맨드·가드 구성.

## 목적

커머스는 **돈이 지나가는 경로**(결제·환불·포인트·기프트카드)와 **경계**(서비스 간 코드·DB 의존 0)가
동시에 걸린다. 본 하네스는 **5계층**으로 분리해 운영한다 — (1) 도메인 전문 서브에이전트(판단 위임),
(2) 서비스별 강제 규칙 스킬(온디맨드 지식), (3) 운영/리뷰 커맨드(워크플로 진입점),
(4) 돈 경로·경계 가드와 검증 게이트(기계 차단), (5) 라우터·텔레메트리(권장 주입과 관측).

원칙: **결정적인 것은 훅·게이트로 강제, 판단이 필요한 것은 에이전트로 위임, 작성과 검증은 분리.**
계층 (4)·(5)는 `scripts/harness/` 에 저장소 추적으로 구현되어 **플러그인·MCP 없이도 동작**한다
(이 하네스의 이식성 전제).

## 디렉토리 구조

하네스는 **두 축**으로 나뉜다 — `.claude/`(모델에게 주는 지식·역할)와 `scripts/harness/`(기계가 강제하는 실행 코어).
전자는 플러그인·런타임에 따라 로드가 달라지지만, 후자는 **저장소에 추적되어 CI·새 클론·Codex 에서도 동일하게 동작**한다.

```
.claude/
├── agents/                            # 서브에이전트 (별도 컨텍스트, 역할 위임)
│   ├── db-query-architect.md          # DB 쿼리/인덱스/ES 매핑 설계
│   ├── doc-maintainer.md              # 문서 일관성 유지 (API/ADR/README)
│   ├── hexagonal-arch-reviewer.md     # 포트/어댑터 경계 + 서비스 간 의존 방향 검증
│   ├── security-auditor.md            # 결제·환불 보안 감사
│   └── event-contract-reviewer.md     # cross-service 이벤트 계약 드리프트·Outbox·멱등 검토 (ADR 0024)
├── skills/                            # 온디맨드 절차적 지식 (SKILL.md)
│   ├── order-commerce-rules · organization-domain-rules · board-domain-rules · operation-signal-rules
│   │                                  # 도메인 규칙 4종 (education 은 미보유 — 아래 커버리지 현황)
│   ├── money-safety · idempotency-and-events                       # 횡단 규칙
│   ├── incident-runbooks · compliance-review · delta-review        # 운영/리뷰
│   ├── debugging-discipline · tdd-discipline · verify-before-done  # 절차 규율(플러그인 독립 — 외부 스킬 위임 금지)
│   ├── msa-service-wiring · event-contract-change                  # 확장 절차 (서비스 배선·이벤트 계약)
│   └── oo-score · hookify-to-guard                                 # OO 5축 재채점(LLM 판정) · 훅 규칙 → guard 이식
├── commands/                          # 슬래시 커맨드 (워크플로 진입점)
│   ├── compliance-scan · delta-review # 감사·리뷰
│   ├── ai-dev-team.md                 # 전사 역할 산출물 일괄 생성
│   └── agents/                        # 역할별 산출물 생성 서브커맨드
├── settings.json                      # 훅·권한 (PreToolUse 가드·라우터, SessionStart 텔레메트리)
└── harness/                           # 하네스 런타임 (gitignore — logs/ 텔레메트리 jsonl, state/ 라우터 세션 상태)

scripts/harness/                       # ★ 실행 코어 — 저장소 추적, 플러그인·MCP 0 의존 (CI 에서 그대로 재실행)
├── guard.mjs · hooks/pre-commit · install-hooks.mjs   # 불변식 가드 3중 강제(실시간·커밋·CI)
├── skill-router.mjs                   # 편집 경로 → *-rules 스킬 리마인더 주입 (권장의 기계화)
├── ci-verdict.mjs                     # CI 판정 조회 — cancelled·skipped 를 통과로 세지 않는다
├── telemetry.mjs · telemetry-report.mjs · session-metrics.mjs   # 관측 계층 (적재·집계·KPI)
├── report-freshness.mjs               # 리포트 신선도 — 낡은 XML 을 근거로 인용하는 것을 막는다
├── manifest.json                      # 하네스 구성요소 추적 목록 — CI 가 git ls-files 로 실존 검증
└── test/*.test.mjs                    # 하네스 자기 테스트 — `node --test "scripts/harness/test/*.test.mjs"`
                                       #   (개수는 세어 쓴다: `git ls-files 'scripts/harness/test/*.test.mjs' | wc -l`)
```

## 대상 코드베이스

- **2 마이크로서비스** + API Gateway + `shared-common`(버전드 1.0.0) · **DB-per-service** ·
  서비스 간 연계는 Kafka 이벤트뿐 — **cross-DB 0 · cross-code 0**(이것이 이 하네스가 지키는 핵심 불변식)
- 서비스 로스터·포트·DB·모듈 경계·컨벤션 → `CLAUDE.md`

## 서비스별 규칙 스킬 (온디맨드 로드)

`order-commerce` · `organization-domain` · `board-domain` · `operation-signal` — **4종**.
각 도메인 로직 작성·수정·리뷰 시 해당 `*-rules` 스킬이 강제 규칙(상태머신·정책·경계)을 로드한다.
로드는 규율이 아니라 `skill-router.mjs` 가 편집 경로를 보고 **자동 주입**한다(아래 "강제 지점").

> **커버리지 현황**: 규칙 도메인 5개 중 4개가 전용 `*-rules` 스킬 + 라우터 `ROUTES` 행을 갖는다
> (둘은 같은 사실의 두 표현 — `skill-router.test.mjs` 가 회귀 방지).
> **미충족 1건 — `education` 도메인**: operation-service 의 슬라이스이지만 전용 규칙 스킬이 없다
> (현재 정본은 PRD `docs/plan/prd/education-service.md`). 라우터는 이 슬라이스 경로를 관제 규칙에서
> **제외만** 하고 라우팅하지 않는다.

> **에이전트 로스터 설계 원칙 (의도된 공백)**: 전용 서브에이전트는 **고위험·상태보존 축**
> (이벤트 계약·헥사 경계·보안·쿼리)에만 둔다. 운영·게시판·교육처럼 상태 변이·금액 리스크가 낮은 축은
> **`*-rules` 스킬 + ArchUnit 게이트로 커버하는 것이 의도된 설계**다 — 도메인마다 에이전트를 만들지
> 않는다(로스터 비대화 = 안티패턴).

## 라우팅 맵 (작업 트리거 → 진입점) — 판단 전 반드시 스캔

> 유형: 🤖=서브에이전트(별도 컨텍스트) · 📘=스킬(온디맨드 규칙) · ⌘=슬래시 커맨드(워크플로) · 🚦=기계 게이트
>
> | 작업 트리거 | 진입점 |
> | --- | --- |
> | 주문·결제·환불·재고 로직 작성·변경 | 📘`order-commerce-rules`+`money-safety` → 🤖`hexagonal-arch-reviewer` |
> | 포인트·기프트카드 원장 | 📘`order-commerce-rules`+`money-safety` (로트 FEFO·환불 복원·되살리지 않는다) |
> | 조직·멤버십·역할(OWNER/MANAGER/STAFF) | 📘`organization-domain-rules` (발행 전용 경계·활성 OWNER ≥1) → 🤖`event-contract-reviewer` (페이로드 변경 시) |
> | 게시판 정의·스킨·접근 정책 | 📘`board-domain-rules` (정의가 글 규칙 소유·스킨↔정책 정합·역할 allowlist·발행 0/소비 0·메뉴는 order 소유) |
> | 관제 신호·인시던트·이상탐지 | 📘`operation-signal-rules` (절대 throw 금지·Outbox 미사용·webhook 항상 200) |
> | 이벤트 발행·컨슈머·멱등 | 📘`idempotency-and-events` → 🤖`event-contract-reviewer` (schema↔producer↔consumer 3자 정합·Outbox·멱등) |
> | cross-service 토픽 추가·페이로드 변경 | 📘`event-contract-change` (스키마·샘플·양방향 계약 테스트 배선) → 🤖`event-contract-reviewer` → 🚦이벤트 계약 테스트 |
> | 신규 도메인 추가 / 배선 404 | 📘`msa-service-wiring` (5곳 배선 체크리스트) → 🚦`gateway-route-gate` |
> | 쿼리·인덱스·ES 매핑·성능 | 🤖`db-query-architect` |
> | MSA 경계 변경 | 🤖`hexagonal-arch-reviewer` → 🚦ArchUnit (_코드 의존 0 / cross-DB 0_ 위반 차단) |
> | OO 설계 채점·리팩터링 회귀 판정 | 📘`oo-score` (3인 패널 중앙값 ≥9.5) — 결정적 불변식은 🚦`guard.mjs` OO-\* + `oo-gate.test.mjs` 가 선차단 |
> | 금액 다루는 코드 | 📘`money-safety` (BigDecimal 강제·라운딩·직렬화) |
> | PR·브랜치 diff 리뷰 착수 ("어디부터 볼까") | 📘`delta-review` (경로 시그널 → 위험축, 세로=안에서 밖으로·가로=프로듀서/계약/컨슈머 3자) → ⌘`/delta-review` |
> | 릴리즈 전 보안·컴플라이언스 | 🤖`security-auditor` + ⌘`/compliance-scan` (diff PII/이력/감사/권한) |
> | 온콜·장애·알람 | 📘`incident-runbooks` + `docs/plan/runbook/` |
> | 기능 구현·버그픽스 착수 | 📘`tdd-discipline` (실패 테스트 먼저 → 🚦JaCoCo 가 정답) — 라우터가 세션 첫 소스 편집에 1회 주입 |
> | 버그·테스트 실패·예상 밖 동작 | 📘`debugging-discipline` (원인 규명 전 수정 금지 · 가설 3연속 기각 시 중단) |
> | "완료" 선언·커밋 직전 | 📘`verify-before-done` (DoD 게이트 실행·증거 병기·자기 승인 금지) |
> | 전사 역할 산출물 일괄 | ⌘`/ai-dev-team` (+ `commands/agents/*` 서브커맨드) |
> | hookify 규칙 생성·수정 / "훅 굳혀줘" | 📘`hookify-to-guard` (캡처는 임시, 정본은 guard.mjs 3중 강제 — 이식 후 원본 삭제) |
>
> **원칙:** 결정적인 것은 🚦게이트로 강제 · 판단 필요한 것은 🤖에이전트로 위임 · 작성과 검증은 분리(자기 승인 금지).

## 강제 지점 (하네스가 실제로 개입하는 순간)

문서 규율이 아니라 **훅으로 배선된 실행 지점**이 정본이다. 배선은 `.claude/settings.json` ·
`scripts/harness/hooks/pre-commit` · `.github/workflows/harness-guard.yml` 세 곳에만 존재한다.

> | 시점 | 트리거 | 실행 | 실패 시 |
> | --- | --- | --- | --- |
> | 파일 편집 직전 | PreToolUse `Write\|Edit\|MultiEdit` | `guard.mjs --hook` | **exit 2 = 편집 차단** |
> | Bash 명령 실행 직전 | PreToolUse `Bash` | `guard.mjs --hook-bash` | **exit 2 = 명령 차단**(BLOCK) / WARN 은 additionalContext 만 |
> | 파일 편집·스킬 호출 직전 | PreToolUse `Write\|Edit\|MultiEdit\|Skill` | `skill-router.mjs --hook` | 차단 없음 — 스킬 리마인더 주입(권장) |
> | 세션 시작 | SessionStart | `telemetry-report.mjs --hook` | 차단 없음 — 가드 차단 통계·라우터 순응률 요약 |
> | 커밋 직전 | git pre-commit | `guard.mjs --staged` | **커밋 차단** (`--no-verify` 우회 금지) |
> | PR·push | `harness-guard.yml` | `guard.mjs --list` + `--deleted-list` + 매니페스트 추적 검증 + 고아 설정 파라미터 감사 | **CI FAIL** |

설치: `node scripts/harness/install-hooks.mjs` (git hook 을 저장소 추적 스크립트로 연결).

## 검증 게이트 (ground truth — 모델 주장이 아니라 기계 판정)

```bash
# 저장소 규율 게이트 전수
node --test "scripts/harness/test/*.test.mjs"

# 백엔드 — 테스트 + JaCoCo LINE 90%
./gradlew :order-service:test :order-service:jacocoTestCoverageVerification
./gradlew :operation-service:test :operation-service:jacocoTestCoverageVerification

# 프론트 — 타입체크 + 테스트(커버리지 임계 lines/statements 90)
cd frontend && npx tsc -p tsconfig.app.json --noEmit && npx vitest run
```

주요 게이트와 그것이 잡는 것:

| 게이트 | 잡는 것 |
| --- | --- |
| `guard.test.mjs` | 가드 규칙 자체의 회귀 — 각 규칙마다 위반/정상 픽스처 쌍 |
| `oo-gate` | 도메인 public setter · generic IAE · 봉인 애그리거트 · 전이표 enum |
| `gateway-route-gate` | 컨트롤러는 있는데 게이트웨이 라우트가 없다 |
| `spa-fallback-gate` | 화면 URL 이 백엔드 API 와 겹쳐 새로고침 때 JSON 이 렌더된다 |
| `menu-route-gate` | 메뉴 시드 ↔ 프론트 폴백 ↔ App.tsx 라우트 3자 드리프트 |
| `api-screen-gate` | 부르는 화면이 없는 컨트롤러 — 부채 예산은 내려가기만 한다 |
| `kafka-topic-gate` · `kafka-publisher-gate` | 카탈로그 미등재 토픽 · 발행부↔카탈로그 드리프트 |
| `topic-consumer-gate` | 발행만 하고 아무도 듣지 않는 토픽 (PUBLISH_ONLY 미등록) |
| `outbox-poller-gate` | 컨슈머가 있는데 DLT 배선이 없다 / 폴러 스캔이 안 닿는다 |
| `scheduler-lock-gate` | shedlock 테이블을 가진 모듈에 락 없는 `@Scheduled` |
| `security-matcher-gate` | 민감 경로에 인가 매처가 안 걸린 메서드 구멍 |
| `async-query-gate` | 비동기 경계 뒤의 동기 조회(테스트 플레이키의 근원) |
| `aop-proxy-gate` · `tx-rollback-gate` | 자기 호출로 무력화된 `@Transactional` · 롤백되지 않는 예외 |
| `coverage-scope-gate` · `sonar-coverage-gate` | 커버리지 게이트가 **공전**하는 것(측정 대상 0개) |
| `ci-verdict-gate` | 필수 체크 표가 워크플로에서 떨어져 나가는 것 |
| `frontend-typecheck-scope-gate` | tsconfig exclude 로 검사에서 빠진 프론트 소스 |
| `dockerignore-gate` · `gradle-cache-mount-gate` | 빌드 컨텍스트·캐시 마운트의 조용한 함정 |
| `deploy-roster-gate` | 모듈이 CI 이미지 매트릭스·k3s 빌드 매핑·프로메테우스 스크레이프 중 어디선가 빠지는 것 — 실패가 아니라 **누락**이라 로그가 정상으로 보인다 |

## 하드스톱 — 절대 금지

정본은 `CLAUDE.md` 의 `🚫 핵심 가드레일`. 요약:

- order ↔ operation 코드 import · DB 조인
- 도메인이 어댑터를 import / 포트 우회
- 금액에 `double`/`float`
- 포인트·기프트카드 로트를 되살리는 UPDATE
- 셀러 리소스 식별자를 요청 파라미터로 신뢰(IDOR)
- `main` 직접 push · `git commit --no-verify`

## 완료 판정(DoD) — 선언 전 이 게이트를 통과했는가

1. 영향 모듈의 `:test` + `:jacocoTestCoverageVerification` 통과 (LINE 90%)
2. 프론트를 만졌다면 `tsc --noEmit` + `vitest run` 통과
3. 컨트롤러·보안 표면·메뉴·라우트를 만졌다면 `node --test "scripts/harness/test/*.test.mjs"` 전수
4. 변경 파일 가드 `node scripts/harness/guard.mjs --list changed.txt` 통과
5. **증거를 병기한다** — 게이트 출력 없이 "통과"를 주장하지 않는다.
   통합 테스트는 Docker 없으면 skip 되므로 **skip 수를 함께 확인**한다.

## 드리프트 방지 규약 (문서 최신성)

```bash
# 1) 휘발성 수치는 필요할 때 직접 센다(문서에 박제하지 않는다)
git ls-files 'scripts/harness/test/*.test.mjs' | wc -l          # 게이트 파일 수
ls shared-common/src/testFixtures/resources/contracts/events/*.schema.json | wc -l   # 계약 토픽 수

# 2) 필수 CI 표는 ci-verdict-gate 가 워크플로와 대조한다(수동 확인 불필요)
node --test scripts/harness/test/ci-verdict-gate.test.mjs
```

## 확장 가이드 (하네스를 늘릴 때)

- **결정적 불변식**이면 스킬이 아니라 `guard.mjs` 규칙 + `*-gate.test.mjs` 로 만든다.
  "문서에 적어 두자"는 이 저장소에서 실패한 방법이다.
- 새 게이트는 반드시 **자기검증 테스트**를 함께 둔다 — 스캔이 비었는데 통과하는 게이트는
  켜져 있는데 아무것도 재지 않는다(실측 사례가 있어 `coverage-scope-gate` 가 생겼다).
- 새 규칙 스킬을 만들면 `skill-router.mjs` 의 `ROUTES` 에 경로를 함께 등록한다 —
  둘은 같은 사실의 두 표현이고 `skill-router.test.mjs` 가 회귀를 막는다.

## 관련 문서

- 아키텍처·컨벤션 정본: [`CLAUDE.md`](./CLAUDE.md) · Codex 판: [`AGENTS.md`](./AGENTS.md)
- 기능 명세: [`SPEC.md`](./SPEC.md) · 구조: [`STRUCTURE.md`](./STRUCTURE.md) · 개요: [`ARCHITECTURE.md`](./ARCHITECTURE.md)
- 결정 기록: [`docs/adr/`](docs/adr/) · 러너북: [`docs/plan/runbook/`](docs/plan/runbook/)
