# Shop — 이커머스 쇼핑몰 MSA

> 이 문서는 **에이전트용 작업 지침** — 아키텍처 경계, 코딩 컨벤션, 빌드·보안에 집중한다.
>
> - **기능·API·유스케이스 상세** → [`SPEC.md`](./SPEC.md) (사람용 기능명세)
> - **서비스별 강제 도메인 규칙** → `*-domain-rules` / `*-rules` 스킬(온디맨드 로드:
>   order-commerce · organization-domain · board-domain · operation-signal — 4종.
>   education 은 전용 규칙 스킬이 없다: PRD `docs/plan/prd/education-service.md` 가 정본)
> - **사용자 문서** → [`README.md`](./README.md) · **아키텍처 결정** → [`docs/adr/`](docs/adr/)
> - **기술 스택·빌드 커맨드·인프라** → [`docs/DEVELOPMENT.md`](./docs/DEVELOPMENT.md) (참조성 — 필요 시 조회)

## 🚫 핵심 가드레일 (위반 시 아키텍처·회계 손상 — 절대 금지)

- **MSA 경계**: `order` 와 `operation` 은 서로를 import 하지 않고 DB 를 조인하지 않는다.
  연계는 **Kafka 이벤트로만**. 서비스 간 HTTP 호출도 지금은 0 이다.
- **헥사고날**: 도메인(`domain/`)이 어댑터(`adapter/`)를 import **금지**. 포트 우회 **금지**(ArchUnit 강제).
- **슬라이스 경계**: `organization`(order 안) · `board`·`education`(operation 안)은 형제 도메인을
  import 하지 않는다 — 각 `*ArchitectureTest` 가 강제한다.
- **금액**: 금액에 `double`/`float` **금지** — `BigDecimal` 강제, 라운딩 정책 보존.
- **원장(포인트·기프트카드)**: 잔액은 계산 결과지 저장값이 아니다. 로트를 되살리지 않는다
  (EXPIRED·REVOKED 는 신규 로트 발급으로 되돌린다 — 역분개 원칙).
- **인가(IDOR)**: 셀러 리소스 식별자를 요청 파라미터로 신뢰 **금지** — JWT 주체(userId)에서 파생 + 소유권 대조(403).
- **커밋**: 브랜치는 **`main` 하나**다(2026-08-25). 항목별 개별 커밋 — 리뷰·롤백이 쉬워진다.

> 위 가드레일은 **기계로 강제된다**(문서 규율 아님): 저장소 추적 가드 `scripts/harness/guard.mjs` 가 실시간
> PreToolUse(exit 2 차단)·git pre-commit(`node scripts/harness/install-hooks.mjs`)·CI(`.github/workflows/harness-guard.yml`) 3중으로
> 위반을 차단한다(플러그인 독립). 하네스 구성 정본은 [`HARNESS.md`](./HARNESS.md).

## 프로젝트 개요

회원·상품·장바구니·주문·결제(포인트·기프트카드 원장 포함)·쿠폰·리뷰·배송·대량주문·셀러등급·조직/멤버십과
운영관제·알림·게시판·교육을 **2개 마이크로서비스 + API Gateway** 로 나눈 헥사고날 백엔드.

- **DB-per-service** — order = `inter`(스키마 `opslab`), operation = `lemuel_operation`.
  (order 만 DB명이 환경별로 갈린다 — compose `inter` / 로컬 기본 `opslab`. "opslab" 은 전 환경 공통 **스키마**명.)
- 서비스 간 연계는 **Kafka 이벤트로만** (코드·DB 직접 의존 0).
- 독립 서비스였다가 흡수된 슬라이스가 넷 있다: `organization`(→ order, ADR 0042),
  `board`·`education`(→ operation, ADR 0043), `notification`(→ operation, ADR 0041).
  **사라진 것은 프로세스와 DB뿐이다** — REST 경로와 이벤트 계약은 불변이고, 경계는 ArchUnit 이 계속 강제한다.

## 기술 스택 (요지)

**Java 25 · Spring Boot 4.0.7 · Gradle(Kotlin DSL) 멀티모듈 · PostgreSQL 17 · Kafka(Redpanda) · Flyway.**
프론트는 React 19 · TypeScript · Vite · Vitest.
전체 표(검색·PG·배치·캐시·PDF·관측·RateLimit 등) → [`docs/DEVELOPMENT.md`](./docs/DEVELOPMENT.md).

## 모듈 구조

```
shop/                             # Gradle 멀티 모듈 루트
├── settings.gradle.kts           # 3 모듈 선언 = 2 서비스 + gateway (shared-common 은 composite build)
├── build.gradle.kts              # 부모 빌드 (subprojects 공통 설정)
├── shared-common/                # 📦 java-library: common.{audit, config, exception, outbox, ratelimit, pdf}
├── order-service/                # 🛒 Commerce (8088, 스키마 opslab) — user·order·bulkorder·payment·point·giftcard·
│                                 #    cart·shipping·product·category·coupon·review·game·menu·rbac·commoncode·
│                                 #    sellertier·auditconsole
│                                 #    + **organization 슬라이스**(셀러/기업 조직·멤버십 OWNER/MANAGER/STAFF — ADR 0042).
│                                 #    이벤트 발행 전용(4토픽, 컨슈머 0). 경계는 OrganizationArchitectureTest 가 강제
├── operation-service/            # 🖥️ Operation (8092, lemuel_operation) — 운영 관제(인시던트·신호·이상탐지)
│                                 #    + notification(알림 팬아웃·푸시 SSE — ADR 0041)
│                                 #    + **board 슬라이스**(메타 주도 게시판: 정의 1행 = 게시판 1개, 프론트 단일 라우트가
│                                 #      스킨(LIST/GALLERY/FAQ/QNA)으로 렌더. 발행 0·소비 0, 권한=역할 allowlist,
│                                 #      메뉴 등록은 관리 화면이 order `/admin/menus` 직접 호출)
│                                 #    + **education 슬라이스**(과정·차시·게시 상태·ADMIN 콘텐츠 관리,
│                                 #      CoursePublished Outbox — operation 의 유일한 발행 경로)
│                                 #    shared-common 제한 스캔. 슬라이스 경계는 각 ArchitectureTest 가 강제
└── gateway-service/              # 🚪 API Gateway (8080) — 라우팅만(자체 인증 필터 없음)
```

- 각 서비스의 **책임·API·유스케이스는 [`SPEC.md`](./SPEC.md), 강제 규칙은 `*-rules` 스킬** 참조.
- 위 트리는 **에이전트용 경계 요약**(포트·DB·shared-common 의존 방식) — 전체 디렉토리·모듈 트리 정본은
  [`STRUCTURE.md`](STRUCTURE.md).

## 헥사고날 아키텍처 (각 서비스 내부)

```
{service}/src/main/java/github/lms/lemuel/{domain}/
├── domain/                 # 도메인 모델 (순수 POJO, 프레임워크 의존 0)
├── application/port/{in,out}/ · application/service/   # UseCase 인터페이스·포트·구현
└── adapter/
    ├── in/{web,kafka,batch,scheduler}/   # REST · Kafka 컨슈머 · Spring Batch · 스케줄러
    └── out/{persistence,external,event,search,pdf}/    # JPA · PG · Outbox 발행 · ES · PDF
```

- **헥사고날 강제**: ArchUnit 으로 패키지 의존 방향 검증. 도메인은 어댑터를 import 하지 않는다.

## 이벤트·멱등 (Outbox + Kafka)

- **Outbox**: DB tx 안에서 `outbox_events` INSERT → 멀티워커 폴러(FOR UPDATE SKIP LOCKED, 기본 2s) 가 Kafka 발행.
- **3단 멱등 방어**: ① `outbox_events.event_id UUID UNIQUE` → ② 컨슈머 `processed_events (consumer_group, event_id)` PK →
  ③ 도메인 UNIQUE.
- **이벤트 계약-as-code (ADR 0024)**: cross-service 토픽의 JSON Schema + 정본 샘플이
  `shared-common/src/testFixtures/resources/contracts/events/` 에 단일 출처. 프로듀서·컨슈머 **양방향 계약 테스트**로
  드리프트를 빌드 시점 차단. 소비: `testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))`.
- **토픽 전송 속성 (ADR 0035)**: 파티션·보존기간·순서키·소유 모듈의 정본은
  `shared-common/src/main/resources/kafka/topic-catalog.json`. 메시지 키가 outbox `aggregateId` 이므로
  **파티션 수 변경 = 키 재해시 = 순서 보장 소급 붕괴** — 되돌릴 수 없다. 토픽을 만드는 주체는 발행 모듈
  하나뿐이며(`app.kafka.topic.owner`), 프로비저너는 없는 토픽만 만들고 기존 파티션은 절대 늘리지 않는다.
  새 토픽은 카탈로그 등록 필수 — 누락 시 `kafka-topic-gate.test.mjs` 가 CI 에서 FAIL.
- **발행 전용 토픽**: 이 저장소는 커머스·운영만 담으므로, 하류 소비자(정산·계정계 등)가 밖에 있는 토픽이 많다.
  그런 토픽은 `topic-consumer-gate.test.mjs` 의 `PUBLISH_ONLY` 에 **사유와 함께** 등록해야 한다 —
  등록하지 않으면 "발행만 하고 아무도 듣지 않는 토픽"으로 CI 가 FAIL 한다.
- 토픽 목록·프로듀서/컨슈머 매핑 → [`SPEC.md`](./SPEC.md) §5. 이벤트/멱등 코드 작성 규칙 → `idempotency-and-events` 스킬.
  토픽 추가·페이로드 변경 절차 → `event-contract-change` 스킬.

## 도메인 규칙 (요지 — 상세는 SPEC.md §4 + `*-rules` 스킬)

- **상태머신은 도메인이 강제한다** — 비정상 전이 차단(예: `OrderStatus.canTransitionTo()` → `Order.transitionTo()`).
  전이표: SPEC.md §4 (Payment/Order/Organization/Membership/Point/GiftCard/Course).
- **금액은 BigDecimal 강제**, 라운딩 정책 보존.
- **포인트·기프트카드는 원장이다**: 로트 단위 FEFO 소비, 환불 시 로트 복원, 소멸 배치.
  EXPIRED·REVOKED 로트는 되살리지 않는다.
- **소유권(IDOR 방지)**: 셀러 리소스 식별자는 요청이 아니라 JWT 주체(userId)에서 파생, 조회/변경은 소유권 대조(403).

## 코딩 컨벤션

- 헥사고날(Ports & Adapters), 도메인 순수 POJO, in/out 포트 분리.
- **DB 마이그레이션**: Flyway. 신규는 **반드시** `V{YYYYMMDDhhmmss}__`(예: `V20260825200000__`).
  정수 번호는 서비스별 기준선(order V50 · operation V4 · marketing V5 · partner V2)에서 닫혔다 —
  기준선 위의 정수는 빈 DB(CI)에서만 통과하고 이력이 있는 배포 DB 에서 out-of-order 로 깨진다.
  `migration-version-gate` 가 강제한다. 배경·회복 절차 → [docs/db-migrations.md](docs/db-migrations.md).
- **테스트**: 도메인 → 서비스 → 컨트롤러 → 통합 순. 통합은 Testcontainers(Docker 필요 — 없으면 skip).
- **커버리지 게이트**: JaCoCo CI **LINE 최소 90%**, 핵심 도메인 패키지 INSTRUCTION 80% 강제(`build.gradle.kts`).
  adapter in/out 서브패키지는 게이트 제외(통합 테스트로 별도 검증). 측정은 게이트 태스크가 정답.
  프론트도 같은 기준선이다 — `vite.config.ts` thresholds(lines·statements 90).
- **OO 구조 게이트**: 도메인 public setter·@Setter/@Data 금지, order 도메인 generic IAE 금지,
  코어 애그리거트는 rehydrate/팩토리 전용 — `guard.mjs` OO-* 규칙(실시간)과 `oo-gate.test.mjs`(CI 전수)가
  기계 강제. 5축 점수 재채점(패널 중앙값 ≥9.5)은 `oo-score` 스킬.

## 작업 프로토콜 / Definition-of-Done

- **완료 판정은 테스트·게이트가 정답**(LLM 판단 아님): `./gradlew :<module>:test` +
  `:<module>:jacocoTestCoverageVerification`(LINE 90%) 통과를 확인한 뒤 완료를 선언한다.
- **절차 규율(플러그인 독립, `.claude/skills/` 자체 내재)**: 구현·버그픽스 착수 전 → `tdd-discipline`,
  버그·테스트 실패 조사 → `debugging-discipline`, "완료" 선언·커밋 직전 → `verify-before-done` 스킬 로드.
- **커밋**: `main` 에 항목별 개별 커밋(리뷰·롤백 용이). **필수 CI 6종**: `Detect changed paths` · `Backend - Build/Test/JaCoCo/SonarCloud` · `Frontend - Production Build & Quality` · `Frontend - Tests` · `guard`(harness-guard) · `SAST (Semgrep OSS)`.
  **다만 이 저장소의 `main` 은 실제로 보호돼 있지 않다**(2026-08-25 실측: `protected: false`,
  required status checks 0건). 위 6종은 *돌지만 막지는 않는다* — 초록을 확인하는 건 사람 몫이다.
  브랜치 보호를 켜기 전까지는 그렇게 알고 있을 것.
  **`cancelled` 는 통과가 아니다** — PR 은 최신 커밋이 이기므로 중간 커밋의 실행은 취소되는데
  빨간 X 가 안 남는다. 판정은 `node scripts/harness/ci-verdict.mjs [sha]` 로 체크 단위 확인.
  **넓은 변경은 PR 로 올릴 것** — main 직접 push 는 직전 커밋 대비 경로 필터로만 게이트되고,
  전량 검증은 PR 런에서만 돈다(단일 브랜치 운영의 대가, `ci.yml` 헤더 주석 참조).
  PowerShell 에서 커밋 메시지는 `git commit -F <file>`(here-string `@` 누수 회피).
- **흔한 함정**:
  - `JWT_SECRET` 은 운영 필수(기본값 없음, ≥32바이트). 테스트는 부모 `build.gradle.kts` 의 test env 로 주입됨.
  - **범위를 좁힌 테스트 통과는 모듈이 초록이라는 증거가 아니다.** `--tests` 로 자른 실행이 전부 초록인 채로
    모듈 전체 게이트에서 무더기 실패가 드러난 전력이 있다.
  - shared-common 은 composite build 로 로컬 치환 — 변경이 의존 서비스에 즉시 반영(별도 publish 불필요).
  - 제한 스캔 서비스(operation)에 shared-common 빈(JwtUtil·필터 등) 추가 시 `@Import` 필요(전역 스캔 안 됨).
  - 새 도메인/서비스는 코드만으론 안 붙는다 — 스캔·JPA·gateway·nginx·Dockerfile 5곳 배선(→ `msa-service-wiring` 스킬).
    이 중 gateway·nginx 누락은 `gateway-route-gate.test.mjs` 가 CI 에서 잡는다(서비스는 401 인데 게이트웨이는 404).
    외부 미노출이 의도면 게이트 목록에 사유 등록.
  - **새 화면 = 라우트 + 메뉴 2스텝**: 네비게이션 정본은 `menus` 테이블이다(프론트 셸은 `GET /api/menus/me` 로 그린다).
    ① `App.tsx` 라우트 추가 ② 시드 마이그레이션 + `frontend/src/data/menuFallback.ts` 에 메뉴 행 추가. 메뉴에 넣지
    않을 화면이면 `menu-route-gate.test.mjs` 의 `ROUTES_WITHOUT_MENU` 에 사유 등록(안 하면 CI FAIL).
    메뉴 **구조**(path·area·parent·권한)는 마이그레이션으로만 — 운영 화면 편집은 표시 속성(이름·순서·노출·아이콘)까지.
    메뉴를 **옮길 때**는 `UPDATE menus SET path = '/new' … WHERE path = '/old'` 로 쓴다(DELETE+INSERT 금지 —
    같은 메뉴에 새 id 를 주는 셈이라 참조가 끊긴다). SET 절의 **첫 컬럼이 path 여야** 게이트가 이동으로 읽는다.
    메뉴를 **지울 때**는 `DELETE FROM menus … path IN (…)` — 자식(parent_id IS NOT NULL) 먼저, 그룹 나중(FK).
    관리자 화면 URL 은 반드시 nginx SPA 폴백 접두사(`/admin/{system|operation|shipping|approvals|login}/**`) **아래** 둔다 —
    밖에 두면 클릭 이동만 되고 **새로고침·북마크·새 탭에서 404**(또는 API JSON 이 그대로 렌더)다. vite dev 엔 nginx 가
    없어 개발에선 안 보인다. `spa-fallback-gate.test.mjs` 가 CI 에서 잡는다. 폴백 목록에 이름을 더하는 것은 대개
    오답이다 — 같은 URL 의 백엔드 API 를 프론트가 못 부르게 된다(그래서 화면 URL 을 옮기는 쪽이 정답).
  - CRLF 파일을 `sed -i` 로 편집하면 전체 라인엔딩이 churn — Edit 도구로 해당 줄만 수정.

## 보안

| 항목                        | 설정                                                                                                                                    |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| 인증                        | JWT (HS256) — `shared-common.common.config.jwt`. `JWT_SECRET` **운영 필수**(기본값 없음 → 미설정 시 기동 실패, ≥32바이트)               |
| 인가                        | 역할 ADMIN/MANAGER/USER + 경로별 `hasRole`. 셀러 리소스는 소유권 대조(IDOR 방지)                                                        |
| 내부/관리 API 키 필터       | `InternalApiKeyFilter`/`AdminApiKeyFilter` — 키 미설정 시 개발 통과, `app.security.internal-key-required=true`(운영) 면 **fail-closed** |
| 비밀번호 / CORS / RateLimit | BCrypt(cost=12) / 환경변수 화이트리스트 / Bucket4j                                                                                       |
| Actuator                    | 인증 필수, `health.show-details=when-authorized`(미인증엔 상태만)                                                                       |
| Audit / 환불 동시성         | PII 마스킹 + 감사로그 / Pessimistic Lock + Idempotency-Key                                                                              |

> 운영 배포 필수 주입: 강한 `JWT_SECRET`, `app.security.internal-key-required=true`.

> **인프라·빌드/실행 커맨드** → [`docs/DEVELOPMENT.md`](./docs/DEVELOPMENT.md).
