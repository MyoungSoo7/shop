# Shop — 이커머스 쇼핑몰 MSA

> **회원가입에서 배송까지, 그리고 그 뒤의 운영까지 — "정확성을 기계로 강제한" 쇼핑몰 백엔드.**
> 커머스(order)와 운영(operation) 두 축을 **2개 마이크로서비스 + API Gateway** 로 나눈 헥사고날 백엔드다.
> 서비스 간 연계는 **Kafka 이벤트로만** — 코드·DB 직접 의존이 0 이다.
>
> 📐 **전체 구성·아키텍처·패턴·스택 한눈에 → [ARCHITECTURE.md](ARCHITECTURE.md)**
> 📋 **기능 명세(엔드포인트 표면·도메인 규칙·이벤트) → [SPEC.md](SPEC.md)**

[![Java 25](https://img.shields.io/badge/Java-25-orange)](https://www.oracle.com/java/)
[![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0.7-brightgreen)](https://spring.io/projects/spring-boot)
[![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-blue)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Kafka-Redpanda-red)](https://redpanda.com/)
[![React 19](https://img.shields.io/badge/React-19%20%2B%20Vite-61dafb)](https://react.dev/)
[![Hexagonal](https://img.shields.io/badge/Architecture-Hexagonal-purple)](docs/adr/0001-hexagonal-architecture.md)
[![ArchUnit Enforced](https://img.shields.io/badge/ArchUnit-Enforced-success)](order-service/src/test/java/github/lms/lemuel/architecture)

---

## 무엇이 들어 있나

| 축 | 서비스 | 담는 것 |
| --- | --- | --- |
| **커머스** | `order-service` (8088) | 회원·인증 · 상품/옵션/재고 · 카테고리·전시 · 장바구니 · 주문 · 결제(Toss PG·분할결제) · 환불 · **포인트 원장** · **기프트카드 원장** · 쿠폰 · 리뷰 · 배송/배송비정책 · 대량주문 · 셀러등급 · 조직/멤버십 · 관리자 백오피스(RBAC·메뉴·공통코드·감사로그) |
| **운영** | `operation-service` (8092) | 관제(인시던트·신호 버킷·이상탐지) · 알림 팬아웃(다채널 + SSE 푸시) · 게시판(공지·FAQ·Q&A) · 교육 과정 관리 |
| **관문** | `gateway-service` (8080) | 경로 라우팅만 — 자체 인증 필터 없음(인가는 각 서비스가 강제) |
| **공용** | `shared-common` | Outbox 발행 머시너리 · JWT · 감사로그 · RateLimit · PDF (composite build 로 합성되는 버전드 내부 라이브러리) |
| **화면** | `frontend` | React 19 + Vite + TypeScript. 구매자 화면 + 관리자 콘솔 |

---

## 이 저장소가 증명하려는 것

### 1. 헥사고날 경계가 문서가 아니라 강제다

```
{service}/src/main/java/github/lms/lemuel/{domain}/
├── domain/                  # 순수 POJO — 프레임워크 의존 0
├── application/port/{in,out}/ · application/service/
└── adapter/in/{web,kafka,batch}/ · adapter/out/{persistence,external,event,search,pdf}/
```

도메인이 어댑터를 import 하면 **ArchUnit 테스트가 빌드를 깬다.** 포트 우회도 같다.

### 2. 돈이 지나가는 길은 기계가 지킨다

- 금액은 `BigDecimal` 강제 — `double`/`float` 은 실시간 가드가 차단한다.
- 도메인 애그리거트에 public setter 가 없다. 상태 전이는 enum 의 `canTransitionTo()` 선언 전이표로만.
- 포인트·기프트카드는 **원장**이다. 로트 단위 FEFO 소비, 환불 시 로트 복원, 소멸 배치.
- 환불은 비관 락 + Idempotency-Key — 같은 키의 두 번째 요청은 no-op 이다.

### 3. 서비스 간 결합은 이벤트뿐이다

```
DB tx 안에서 outbox_events INSERT
   → 멀티워커 폴러(FOR UPDATE SKIP LOCKED, 2s)
   → Kafka 발행
```

**3단 멱등 방어**: ① `outbox_events.event_id UUID UNIQUE` → ② 컨슈머 `processed_events (consumer_group, event_id)` PK
→ ③ 도메인 UNIQUE 제약.

토픽의 **JSON Schema + 정본 샘플**이 `shared-common/src/testFixtures/resources/contracts/events/` 에
단일 출처로 있고, 프로듀서·컨슈머 **양방향 계약 테스트**가 드리프트를 빌드 시점에 막는다(ADR 0024).
파티션·보존기간·순서키의 정본은 `kafka/topic-catalog.json` 이다(ADR 0035) —
**파티션 수 변경 = 키 재해시 = 순서 보장 소급 붕괴**라 되돌릴 수 없기 때문에 카탈로그가 정본이다.

### 4. "컴파일러가 못 보는 공백"을 게이트가 본다

`node --test "scripts/harness/test/*.test.mjs"` — 게이트 **337건**이 돈다.

| 게이트 | 잡는 것 |
| --- | --- |
| `gateway-route-gate` | 컨트롤러는 있는데 게이트웨이 라우트가 없다 (서비스는 401 인데 게이트웨이는 404) |
| `menu-route-gate` | 메뉴 시드 ↔ 프론트 폴백 ↔ App.tsx 라우트 3자 드리프트 (죽은 링크·유령 화면) |
| `spa-fallback-gate` | 화면 URL 이 백엔드 API 와 겹쳐 새로고침 때 JSON 이 렌더된다 |
| `api-screen-gate` | 부르는 화면이 없는 컨트롤러 — 부채로 등록하지 않으면 FAIL |
| `topic-consumer-gate` | 발행만 하고 아무도 듣지 않는 토픽 |
| `outbox-poller-gate` | 컨슈머가 있는데 DLT 배선이 없다 / 폴러 스캔이 안 닿는다 |
| `scheduler-lock-gate` | shedlock 테이블을 가진 모듈에 락 없는 `@Scheduled` |
| `oo-gate` | 도메인 public setter · generic `IllegalArgumentException` · 봉인 애그리거트 회귀 |
| `security-matcher-gate` | 민감 경로에 인가 매처가 안 걸린 메서드 구멍 |

같은 규칙이 **3중으로** 강제된다: 실시간 PreToolUse 훅(exit 2) · git pre-commit · CI.
설치는 `node scripts/harness/install-hooks.mjs`.

---

## 빠른 시작

### 필요한 것

- JDK 25 · Docker(Compose) · Node 22

### 1) 환경변수

```bash
cp .env.example .env
# JWT_SECRET 은 32바이트 이상이어야 한다 — 미설정이면 기동이 실패한다(fail-closed).
#   openssl rand -base64 32
```

### 2) 전체 기동 (Docker Compose)

```bash
cd frontend && npm ci && npm run build && cd ..   # ← compose 의 frontend 는 dist 를 마운트한다
docker compose up -d
# → gateway http://localhost:8080 · frontend http://localhost:3000
```

`frontend/dist` 는 git 에 추적되지 않는다(빌드 산출물). 빌드를 건너뛰면 마운트가 빈 디렉터리가 되어
nginx 가 404 만 낸다 — 프로덕션은 `frontend/Dockerfile` 이 이미지 안에서 빌드하므로 영향이 없다.

컨테이너 구성: PostgreSQL 2종(order `inter` / operation `lemuel_operation`) · Elasticsearch ·
Redpanda · Redis · pgbouncer · 앱 3개 · frontend · 관측 7종(exporter 3 + prometheus + alertmanager + tempo + grafana).

### 3) 백엔드만 로컬로

```bash
./gradlew :order-service:bootRun        # 8088
./gradlew :operation-service:bootRun    # 8092
./gradlew :gateway-service:bootRun      # 8080
```

### 4) 프론트엔드

```bash
cd frontend
npm ci
npm run dev        # vite dev
npm run build && npm run preview   # 프로덕션 빌드 확인 — /admin 직접진입은 이쪽에서만 재현된다
```

---

## 검증 (Definition of Done)

완료 판정은 주장이 아니라 **게이트 출력**이다.

```bash
# 백엔드 — 테스트 + JaCoCo LINE 90% 게이트
./gradlew :order-service:test :order-service:jacocoTestCoverageVerification
./gradlew :operation-service:test :operation-service:jacocoTestCoverageVerification

# 프론트엔드 — 타입체크 + 테스트(커버리지 임계 lines/statements 90)
cd frontend && npx tsc -p tsconfig.app.json --noEmit && npx vitest run

# 저장소 규율 게이트 337건
node --test "scripts/harness/test/*.test.mjs"

# 변경 파일 가드(실시간 훅과 같은 규칙)
node scripts/harness/guard.mjs --list changed.txt
```

통합 테스트는 Testcontainers PostgreSQL 을 쓴다 — **Docker 가 없으면 조용히 skip 된다.**
"통과"를 인용하기 전에 skip 수를 확인할 것.

---

## API 라우팅 (Gateway, 8080)

| 경로 | 대상 |
| --- | --- |
| `/auth/**` `/users/**` `/orders/**` `/payments/**` `/products/**` `/categories/**` `/coupons/**` `/reviews/**` `/memberships/**` `/display-sections/**` | order-service |
| `/api/products/**` `/api/categories/**` `/api/tags/**` `/api/payments/**` `/api/points/**` `/api/gift-cards/**` `/api/bulk-orders/**` `/api/organizations/**` `/api/menus/**` | order-service |
| `/admin/{categories,products,pg,menus,common-codes,rbac,payment-expiry,stock-reclaim,seller-tiers,shipments,shipping-policies,option-catalog,display-sections,points,gift-cards,audit-logs,members,reviews,coupons,refunds}/**` | order-service |
| `/api/ops/**` `/api/boards/**` `/admin/boards/**` `/admin/education/**` | operation-service |
| `/api/notifications/stream` | operation-service (SSE 푸시) |

게이트웨이는 **라우팅만** 한다. 인증·인가는 각 서비스의 SecurityConfig 가 강제한다.

---

## 화면을 하나 더 붙일 때 (2스텝 + 1가드)

네비게이션의 정본은 `menus` 테이블이다(프론트 셸은 `GET /api/menus/me` 로 그린다).

1. `frontend/src/App.tsx` 에 라우트 추가
2. 메뉴 시드 마이그레이션(`order-service/.../db/migration/V*__menu_*.sql`) + `frontend/src/data/menuFallback.ts` 에 행 추가

메뉴에 넣지 않을 화면이면 `menu-route-gate.test.mjs` 의 `ROUTES_WITHOUT_MENU` 에 **사유와 함께** 등록한다.

⚠️ 관리자 화면 URL 은 nginx SPA 폴백 접두사(`/admin/{system|operation|shipping|approvals|login}/**`)
**아래** 둔다. 밖에 두면 클릭 이동만 되고 **새로고침·북마크·새 탭에서 404** 다. vite dev 에는 nginx 가
없어 개발 중에는 보이지 않는다 — `spa-fallback-gate.test.mjs` 가 CI 에서 잡는다.

---

## 보안

| 항목 | 설정 |
| --- | --- |
| 인증 | JWT(HS256). `JWT_SECRET` **운영 필수**(기본값 없음 → 미설정 시 기동 실패, ≥32바이트) |
| 인가 | 역할 ADMIN/MANAGER/USER + 경로별 `hasRole`. 셀러 리소스는 JWT 주체에서 파생 + 소유권 대조(IDOR 방지) |
| 내부 API | `InternalApiKeyFilter`(X-Internal-Api-Key) — 키 미설정 시 개발 통과, `app.security.internal-key-required=true`(운영)면 fail-closed |
| 비밀번호 / CORS / RateLimit | BCrypt(cost=12) / 환경변수 화이트리스트 / Bucket4j |
| Actuator | 인증 필수, `health.show-details=when-authorized` |
| 감사 / 환불 동시성 | PII 마스킹 + 감사로그 / Pessimistic Lock + Idempotency-Key |

---

## 문서

| 문서 | 내용 |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 구성·패턴·스택·CI/CD |
| [SPEC.md](SPEC.md) | 기능 명세 — 엔드포인트 표면·도메인 규칙·상태머신·이벤트 카탈로그 |
| [STRUCTURE.md](STRUCTURE.md) | 디렉토리·모듈 트리 |
| [CLAUDE.md](CLAUDE.md) · [AGENTS.md](AGENTS.md) | 에이전트 작업 지침(가드레일·컨벤션) |
| [HARNESS.md](HARNESS.md) | 하네스 구성 정본 — 가드·게이트·스킬 라우팅 |
| [docs/adr/](docs/adr/) | 아키텍처 결정 기록 |
| [docs/plan/prd/](docs/plan/prd/) | 서비스별 역산 PRD |
| [docs/plan/runbook/](docs/plan/runbook/) | 온콜 러너북 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 빌드·실행 커맨드, 인프라 |

---

## 이름에 대하여

자바 패키지 루트는 `github.lms.lemuel` 이고 컨테이너 이름에도 `lemuel-` 접두사가 남아 있다.
이 저장소는 더 큰 플랫폼에서 커머스·운영 두 축을 떼어 낸 것이라, 패키지·컨테이너 이름을 바꾸면
마이그레이션 이력과 이벤트 계약(토픽명 `lemuel.*`)까지 함께 흔들린다. **토픽명은 외부 계약이므로
바꾸지 않는다** — 이름은 유래로 남기고, 경계는 코드와 게이트로 말한다.

---

## 라이선스

[LICENSE](LICENSE)
