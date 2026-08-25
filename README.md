# Shop — 이커머스 쇼핑몰 MSA

> 커머스(order)와 운영(operation) 두 축을 **2개 마이크로서비스 + API Gateway** 로 나눈 헥사고날 백엔드.
> 서비스 간 연계는 **Kafka 이벤트로만** — 코드·DB 직접 의존이 0 이다.
> 규칙은 문서가 아니라 **빌드를 깨는 게이트**로 강제한다.
>
> 📐 구성·패턴·스택 → **[ARCHITECTURE.md](ARCHITECTURE.md)** · 📋 기능 명세·이벤트 카탈로그 → **[SPEC.md](SPEC.md)**

[![Java 25](https://img.shields.io/badge/Java-25-orange)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0.7-brightgreen)](https://spring.io/projects/spring-boot)
[![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-blue)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Kafka-Redpanda-red)](https://redpanda.com/)
[![React 19](https://img.shields.io/badge/React-19%20%2B%20Vite-61dafb)](https://react.dev/)
[![Hexagonal](https://img.shields.io/badge/Architecture-Hexagonal-purple)](docs/adr/0001-hexagonal-architecture.md)
[![ArchUnit Enforced](https://img.shields.io/badge/ArchUnit-Enforced-success)](order-service/src/test/java/github/lms/lemuel/architecture)

---

## 무엇이 들어 있나

| 축 | 모듈 | 담는 것 |
| --- | --- | --- |
| **커머스** | `order-service` (8088) | 회원·인증 · 상품/옵션/재고 · 카테고리·전시 · 장바구니 · 주문 · 결제(Toss PG·분할결제) · 환불 · **포인트 원장** · **기프트카드 원장** · 쿠폰 · 리뷰 · 배송/배송비정책 · 대량주문 · 셀러등급 · 조직/멤버십 · 관리자 백오피스(RBAC·메뉴·공통코드·감사로그) |
| **운영** | `operation-service` (8092) | 관제(인시던트·신호 버킷·이상탐지) · 알림 팬아웃(다채널 + SSE 푸시) · 게시판(공지·FAQ·Q&A) · 교육 과정 관리 |
| **관문** | `gateway-service` (8080) | 경로 라우팅만 — 자체 인증 필터 없음(인가는 각 서비스가 강제) |
| **공용** | `shared-common` | Outbox 발행 머시너리 · JWT · 감사로그 · RateLimit · PDF · **이벤트 계약 픽스처** (`includeBuild` 로 합성되는 버전드 내부 라이브러리, [ADR 0021](docs/adr/0021-shared-common-as-platform-library.md)) |
| **화면** | `frontend` | React 19 + Vite + TypeScript. 구매자 화면 + 관리자 콘솔 |

### 규모 (2026-08-25 실측)

| | |
| --- | --- |
| Java 소스 | main **1,245** · test **455** |
| HTTP 표면 | 컨트롤러 **55** · 매핑 **262** |
| Flyway 마이그레이션 | order **153** · operation **18** |
| Kafka | 토픽 **21** · 계약 픽스처 **21** (1:1 강제) |
| 프론트엔드 | TS/TSX **270** · 테스트 파일 **126** |
| 저장소 규율 게이트 | **336건** / 46 스위트 · 실시간 가드 규칙 **14종** |
| 결정 기록 | ADR **21건** |

수치는 주장이 아니라 재현 가능한 카운트다 — 확인 커맨드는 [검증](#검증-definition-of-done) 절에 있다.

---

## 이 저장소가 증명하려는 것

### 1. 헥사고날 경계가 문서가 아니라 강제다

```
{service}/src/main/java/github/lms/lemuel/{domain}/
├── domain/                  # 순수 POJO — 프레임워크 의존 0
├── application/port/{in,out}/ · application/service/
└── adapter/in/{web,kafka,batch}/ · adapter/out/{persistence,external,event,search,pdf}/
```

도메인이 어댑터를 import 하면 **ArchUnit 이 빌드를 깬다.** 포트 우회도 같다. 세 축으로 검사한다:

- `HexagonalArchitectureTest` — 의존 방향. **일부 규칙에 임시 허용 목록이 남아 있다**(기존 위반분).
  허용 목록은 통과가 아니라 **등록된 부채**이고, 별도 리팩터 태스크로 비워 나가는 중이다.
- `InboundPortReachabilityTest` — 유스케이스를 구현하고 단위 테스트까지 붙였는데 **아무 어댑터도
  호출하지 않아** 런타임에 없는 기능이 되는 결함. 컴파일러도 단위 테스트도 "부르는 사람이 없다"를 못 본다.
- `QueryParamBindingGateTest` — `@Query` 의 이름 파라미터(`:name`)에 `@Param` 바인딩이 빠진 것.

### 2. 돈이 지나가는 길은 기계가 지킨다

- 금액 스코프(payment·point·ledger·payout·recon…)에서 `double`/`float` 은 **실시간 가드가 차단**한다
  (`MONEY-PRIMITIVE`). `new BigDecimal(0.1)` 같은 이진 부동소수 흡수도 별도 규칙으로 막는다.
- 도메인 애그리거트에 public setter 가 없다(`OO-DOMAIN-SETTER`). 상태 전이는 enum 의
  `canTransitionTo()` 선언 전이표로만 — 조건문이 아니라 표라서 누락이 눈에 띈다.
- 포인트·기프트카드는 **원장**이다. 로트 단위 FEFO 소비, 환불 시 로트 복원, 소멸 배치.
- 환불은 비관 락 + `Idempotency-Key` — 같은 키의 두 번째 요청은 no-op 이다.
- 커버리지도 돈 쪽이 더 빡세다: 전역 LINE **90%** 위에, payment·point·order·product·cart·shipping
  의 `domain` 패키지는 INSTRUCTION **80%** 를 따로 요구한다.

### 3. 서비스 간 결합은 이벤트뿐이다

```
DB tx 안에서 outbox_events INSERT
   → 멀티워커 폴러(FOR UPDATE SKIP LOCKED, 2s)
   → Kafka 발행
```

**3단 멱등 방어**: ① `outbox_events.event_id UUID UNIQUE` → ② 컨슈머
`processed_events (consumer_group, event_id)` PK → ③ 도메인 UNIQUE 제약.
어느 한 겹이 뚫려도 다음 겹이 막는다.

토픽의 **JSON Schema + 정본 샘플**이
`shared-common/src/testFixtures/resources/contracts/events/` 에 단일 출처로 있고,
프로듀서·컨슈머 **양방향 계약 테스트**가 드리프트를 빌드 시점에 막는다([ADR 0024](docs/adr/0024-event-contract-as-code.md)).

파티션·보존기간·순서키의 정본은 **`shared-common/src/main/resources/kafka/topic-catalog.json`**
이다([ADR 0035](docs/adr/0035-kafka-topic-catalog.md)). 브로커 설정이 아니라 파일이 정본인 이유는
되돌릴 수 없기 때문이다 — **파티션 수 변경 = 키 재해시 = 이미 발행된 이벤트의 순서 보장 소급 붕괴.**

### 4. "컴파일러가 못 보는 공백"을 게이트가 본다

`node --test "scripts/harness/test/*.test.mjs"` — 게이트 **336건**이 돈다. 잡는 것은 타입 오류가
아니라 **층 사이의 빈틈**이다.

| 게이트 | 잡는 것 |
| --- | --- |
| `gateway-route-gate` | 컨트롤러는 있는데 게이트웨이 라우트가 없다 (서비스는 401 인데 게이트웨이는 404) |
| `menu-route-gate` | 메뉴 시드 ↔ 프론트 폴백 ↔ `App.tsx` 라우트 3자 드리프트 (죽은 링크·유령 화면) |
| `spa-fallback-gate` | 화면 URL 이 백엔드 API 와 겹쳐 새로고침 때 JSON 이 렌더된다 |
| `api-screen-gate` | 부르는 화면이 없는 컨트롤러 — 부채로 등록하지 않으면 FAIL |
| `topic-consumer-gate` | 발행만 하고 아무도 듣지 않는 토픽 |
| `kafka-topic-gate` | 토픽 파티션 수가 코드 밖에서 정해진다 (카탈로그 미등재 토픽) |
| `outbox-poller-gate` | 컨슈머가 있는데 DLT 배선이 없다 / 폴러 스캔이 안 닿는다 |
| `scheduler-lock-gate` | shedlock 테이블을 가진 모듈에 락 없는 `@Scheduled` |
| `tx-rollback-gate` | 체크 예외를 던지는 `@Transactional` 에 `rollbackFor` 가 없다 (조용히 커밋된다) |
| `aop-proxy-gate` | 자기호출로 프록시를 우회해 `@Transactional`·`@Async` 가 안 걸린다 |
| `security-matcher-gate` | 민감 경로에 인가 매처가 안 걸린 메서드 구멍 |
| `oo-gate` | 도메인 public setter · generic `IllegalArgumentException` · 봉인 애그리거트 회귀 |
| `coverage-scope-gate` | 커버리지 스코프가 비어 "위반 없음"으로 통과하는 위장 초록불 |
| `node-version-gate` | `.nvmrc` ↔ `frontend/Dockerfile` major 불일치 |

전체 목록은 `scripts/harness/test/` (게이트 파일 **25개**). 같은 규칙이 **3중으로** 강제된다 —
실시간 PreToolUse 훅(exit 2) · git pre-commit · CI. 설치는 `node scripts/harness/install-hooks.mjs`.

---

## 빠른 시작

**필요한 것**: JDK **25** · Docker(Compose) · Node **24** (`.nvmrc` 가 단일 출처)

### 1) 환경변수

```bash
cp .env.example .env       # 25개 키
# JWT_SECRET 은 32바이트 이상이어야 한다 — 미설정이면 기동이 실패한다(fail-closed).
#   openssl rand -base64 32
```

⚠️ `.env.example` 을 **복사해서** 쓸 것. `application.yml` 에는 기본값 없는 `${VAR}` 가
`JWT_SECRET`·`TOSS_SECRET_KEY`·`MAIL_USERNAME` 등 여러 개 있고, 하나라도 비면 컨텍스트 생성 단계에서
`PlaceholderResolutionException` 으로 죽는다. `docker compose config` 에는 이 변수들이 **안 보인다**
— compose 가 보간하는 게 아니라 `env_file` 로 컨테이너에 그대로 들어가기 때문이다.

### 2) 전체 기동 (Docker Compose)

```bash
cd frontend && npm ci && npm run build && cd ..   # ← compose 의 frontend 는 dist 를 마운트한다
docker compose up -d
# → gateway http://localhost:8080 · frontend http://localhost:3000
```

`frontend/dist` 는 git 에 추적되지 않는다(빌드 산출물). 빌드를 건너뛰면 마운트가 빈 디렉터리가 되어
nginx 가 404 만 낸다 — 프로덕션은 `frontend/Dockerfile` 이 이미지 안에서 빌드하므로 영향이 없다.

compose 서비스 **17개**: PostgreSQL 2종(order `inter` / operation `lemuel_operation`) · pgbouncer ·
Elasticsearch · Redpanda · Redis · 앱 3개 · frontend · 관측 7종(exporter 3 + prometheus +
alertmanager + tempo + grafana). 관측 7종은 `docker compose up -d postgres … frontend` 로 골라 띄우면
빼도 된다. 발행 포트는 전부 `127.0.0.1` 바인딩이라 기본값으로는 LAN 에 노출되지 않는다.

데모(`shop.lemuel.co.kr`)는 david 노드에서 이 compose 로 돌고, 프론트만 LAN 에 노출하기 위해
`deploy/david/docker-compose.override.yml` 을 **명시적으로** 얹는다(루트에 두면 자동 로드되어
모든 로컬 기동이 `0.0.0.0:3000` 을 열게 되므로 일부러 `deploy/` 아래에 둔다).

```bash
docker compose -f docker-compose.yml -f deploy/david/docker-compose.override.yml up -d
```

쿠버네티스로 옮기지 않고 compose 에 남긴 이유는 [ADR 0044](docs/adr/0044-deployment-stays-on-compose.md).

### 3) 백엔드만 로컬로

```bash
./gradlew :order-service:bootRun        # 8088
./gradlew :operation-service:bootRun    # 8092
./gradlew :gateway-service:bootRun      # 8080
```

### 4) 프론트엔드

```bash
cd frontend && npm ci
npm run dev                          # vite dev
npm run build && npm run preview     # /admin 직접진입은 이쪽에서만 재현된다
```

---

## 검증 (Definition of Done)

완료 판정은 주장이 아니라 **게이트 출력**이다.

```bash
# 백엔드 — 테스트 + JaCoCo (전역 LINE 90% + 핵심 도메인 패키지 INSTRUCTION 80%)
./gradlew :order-service:test :order-service:jacocoTestCoverageVerification
./gradlew :operation-service:test :operation-service:jacocoTestCoverageVerification

# 프론트엔드 — 타입체크 + 테스트(커버리지 임계 lines/statements 90)
cd frontend && npx tsc -p tsconfig.app.json --noEmit && npx vitest run

# 저장소 규율 게이트 336건
node --test "scripts/harness/test/*.test.mjs"

# 변경 파일 가드(실시간 훅과 같은 규칙 14종)
node scripts/harness/guard.mjs --list changed.txt
```

⚠️ 통합 테스트는 Testcontainers PostgreSQL 을 쓴다 — **Docker 가 없으면 조용히 skip 된다.**
"통과"를 인용하기 전에 skip 수를 볼 것. 초록불과 "검사했다"는 같은 말이 아니다.

---

## CI/CD

`.github/workflows/` 7종. 본 파이프라인은 `ci.yml` 이고, `changes` 잡이 경로 필터로 백엔드/프론트
갈래를 나눈 뒤 필요한 것만 돈다(동일 ref 재푸시는 `concurrency` 로 취소).

| 잡 | 하는 일 |
| --- | --- |
| `changes` | 경로 필터 — 아래 잡들의 실행 여부를 결정한다 |
| `backend-shared` → `backend-test`(모듈 매트릭스) → `backend-ci` | shared-common 선행 검증 → 모듈별 테스트·리포트 → 빌드·JaCoCo·SonarCloud·SBOM |
| `frontend-ci` · `frontend-tests` | 프로덕션 빌드·품질(→ `frontend-dist` 아티팩트) / vitest·커버리지 |
| `backend-ghcr` · `frontend-ghcr` → `images` | GHCR 푸시 → 이미지 스캔 |
| `production-revision-smoke` | **현재 배포된 리비전**에 Playwright 스모크 — 빌드 성공이 아니라 서비스 상태를 본다 |

보조: `harness-guard.yml`(게이트 3중 강제의 CI 겹) · `semgrep.yml` · `pr-review.yml` ·
`e2e-manual.yml` · `mirror-testcontainers.yml` · `backend-image-emergency.yml`.

---

## API 라우팅 (Gateway, 8080)

게이트웨이는 **라우팅만** 한다. 인증·인가는 각 서비스의 SecurityConfig 가 강제한다.
경로 접두사가 `/api` 와 비-`/api` 로 섞여 있는 것은 **외부 계약이라 정리하지 않은 것**이다.

| 경로 | 대상 |
| --- | --- |
| `/auth/**` `/users/**` `/orders/**` `/payments/**` `/products/**` `/categories/**` `/coupons/**` `/reviews/**` `/memberships/**` `/games/**` `/display-sections/**` | order-service |
| `/api/{products,categories,tags,payments,points,gift-cards,bulk-orders,organizations,menus}/**` | order-service |
| `/admin/{categories,products,pg,menus,common-codes,rbac,payment-expiry,stock-reclaim,seller-tiers,shipments,shipping-policies,option-catalog,display-sections,points,gift-cards,audit-logs,members,reviews,coupons,refunds}/**` | order-service |
| `/api/ops/**` `/api/boards/**` `/admin/boards/**` `/admin/education/**` | operation-service |
| `/api/notifications/stream` | operation-service (SSE 푸시) |

내부 발송 경로(`/internal/notifications/**`)는 **게이트웨이에 올리지 않는다** — 인증 없이 발송하는
경로라 와일드카드로 노출하면 그대로 공개 API 가 된다.

---

## 화면을 하나 더 붙일 때 (2스텝 + 1가드)

네비게이션의 정본은 `menus` 테이블이다(프론트 셸은 `GET /api/menus/me` 로 그린다).

1. `frontend/src/App.tsx` 에 라우트 추가
2. 메뉴 시드 마이그레이션(`order-service/.../db/migration/V*__menu_*.sql`) +
   `frontend/src/data/menuFallback.ts` 에 행 추가

메뉴에 넣지 않을 화면이면 `menu-route-gate.test.mjs` 의 `ROUTES_WITHOUT_MENU` 에 **사유와 함께** 등록한다.

⚠️ 관리자 화면 URL 은 nginx SPA 폴백 접두사(`/admin/{system|operation|shipping|approvals|login}/**`)
**아래** 둔다. 밖에 두면 클릭 이동만 되고 **새로고침·북마크·새 탭에서 404** 다. vite dev 에는 nginx 가
없어 개발 중에는 보이지 않는다 — `spa-fallback-gate` 가 CI 에서 잡는다.

---

## 보안

| 항목 | 설정 |
| --- | --- |
| 인증 | JWT(HS256). `JWT_SECRET` **운영 필수**(기본값 없음 → 미설정 시 기동 실패, ≥32바이트) |
| 인가 | 역할 ADMIN/MANAGER/USER + 경로별 `hasRole`. 셀러 리소스는 JWT 주체에서 파생 + 소유권 대조(IDOR 방지) |
| 내부 API | `InternalApiKeyFilter`(`X-Internal-Api-Key`) — 키 미설정 시 개발 통과, `app.security.internal-key-required=true`(운영)면 fail-closed |
| 비밀번호 / CORS / RateLimit | BCrypt(cost=12) / 환경변수 화이트리스트 / Bucket4j |
| Actuator | 인증 필수, `health.show-details=when-authorized` |
| 감사 / 환불 동시성 | PII 마스킹 + 감사로그 / Pessimistic Lock + `Idempotency-Key` |

---

## 문서

| 문서 | 내용 |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 구성·패턴·스택·CI/CD |
| [SPEC.md](SPEC.md) | 기능 명세 — 엔드포인트 표면·도메인 규칙·상태머신·이벤트 카탈로그 |
| [STRUCTURE.md](STRUCTURE.md) | 디렉토리·모듈 트리 |
| [CLAUDE.md](CLAUDE.md) · [AGENTS.md](AGENTS.md) | 에이전트 작업 지침(가드레일·컨벤션) |
| [HARNESS.md](HARNESS.md) | 하네스 구성 정본 — 가드·게이트·스킬 라우팅 |
| [docs/adr/](docs/adr/) | 아키텍처 결정 기록 21건 |
| [docs/plan/prd/](docs/plan/prd/) | 서비스별 역산 PRD |
| [docs/plan/runbook/](docs/plan/runbook/) | 온콜 러너북 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 빌드·실행 커맨드, 인프라 |

---

## 이름에 대하여

자바 패키지 루트는 `github.lms.lemuel` 이고 컨테이너 이름에도 `lemuel-` 접두사가 남아 있다.
이 저장소는 더 큰 플랫폼에서 커머스·운영 두 축을 떼어 낸 것이라, 이름을 바꾸면 마이그레이션 이력과
이벤트 계약(토픽명 `lemuel.*`)까지 함께 흔들린다. **토픽명은 외부 계약이므로 바꾸지 않는다** —
이름은 유래로 남기고, 경계는 코드와 게이트로 말한다.

---

## 라이선스

[LICENSE](LICENSE)
