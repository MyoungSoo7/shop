# 아키텍처 개요 (Architecture Overview)

> 이 문서는 **"무엇이 어떻게 짜여 있는가"** 를 한 장에 담는다.
> 기능·엔드포인트 상세는 [`SPEC.md`](SPEC.md), 작업 규율은 [`CLAUDE.md`](CLAUDE.md),
> 결정 근거는 [`docs/adr/`](docs/adr/).

---

## 1. 서비스 인벤토리 — 3개 (+ 플랫폼 라이브러리)

| 서비스 | 포트 | DB | 책임 | shared-common |
| --- | --- | --- | --- | --- |
| `order-service` | 8088 | `inter` (스키마 `opslab`) | 커머스 코어 — 회원·상품·장바구니·주문·결제·환불·포인트·기프트카드·쿠폰·리뷰·배송·대량주문·셀러등급·조직/멤버십·관리자 백오피스 | 전역 스캔 |
| `operation-service` | 8092 | `lemuel_operation` | 운영 — 관제(인시던트·신호·이상탐지)·알림(팬아웃/SSE)·게시판·교육 | 제한 스캔(`@Import`) |
| `gateway-service` | 8080 | — | API Gateway (Spring Cloud Gateway, WebFlux). 라우팅만 | 미의존 |
| `shared-common` | — | — | Outbox 발행 머시너리 · JWT · 감사 · RateLimit · PDF · 이벤트 계약 픽스처 | (본체) |

- **DB-per-service**: order 와 operation 은 물리적으로 다른 PostgreSQL 인스턴스를 쓴다.
  order 만 DB 명이 환경별로 갈린다 — compose `inter` / 로컬 기본 `opslab`. "opslab" 은 전 환경 공통 **스키마**명이다.
- **서비스 간 연계는 Kafka 이벤트로만.** 코드 의존 0, DB 조인 0, 서비스 간 HTTP 호출 0(실측).
- `shared-common` 은 **composite build** 로 합성되는 버전드 내부 라이브러리다
  (`includeBuild("shared-common")`). 서비스는 좌표 `github.lms.lemuel:shared-common:1.0.0` 을 선언하고,
  로컬에서는 included build 로 자동 치환된다 — 별도 publish 없이 변경이 즉시 반영된다(ADR 0021).

### 슬라이스 — 프로세스는 하나인데 경계는 여럿

두 서비스 안에는 독립 서비스였다가 흡수된 슬라이스가 있다. **사라진 것은 프로세스와 DB뿐이고,
REST 경로와 이벤트 계약은 불변이다.**

| 슬라이스 | 사는 곳 | 경계 강제 |
| --- | --- | --- |
| `organization` (조직·멤버십) | order-service (ADR 0042) | `OrganizationArchitectureTest` — order 의 다른 도메인 import 금지 |
| `board` (메타 주도 게시판) | operation-service (ADR 0043) | `BoardArchitectureTest` |
| `education` (과정·차시) | operation-service (ADR 0043) | `EducationArchitectureTest` |
| `notification` (알림 팬아웃·SSE) | operation-service (ADR 0041) | 자체 저장소 없음(무영속) |

---

## 2. 적용 아키텍처 (Applied Architecture)

```
{service}/src/main/java/github/lms/lemuel/{domain}/
├── domain/                                  # 도메인 모델 (순수 POJO, 프레임워크 의존 0)
├── application/
│   ├── port/in/   · port/out/               # UseCase 인터페이스 · 아웃바운드 포트
│   └── service/                             # UseCase 구현
└── adapter/
    ├── in/{web,kafka,batch,scheduler}/      # REST · Kafka 컨슈머 · Spring Batch · 스케줄러
    └── out/{persistence,external,event,search,pdf}/   # JPA · 외부 API · Outbox 발행 · ES · PDF
```

- **헥사고날(Ports & Adapters)** — 의존 방향은 항상 안쪽이다. 도메인은 어댑터를 모른다.
- **ArchUnit 이 강제한다.** 도메인이 어댑터를 import 하거나 포트를 우회하면 테스트가 깨진다.
  (ArchUnit 1.4.x 부터 Java 25 바이트코드를 읽는다 — 그 전 버전은 파싱조차 못 한다.)
- **CQRS 는 쓰지 않는다.** 이 저장소의 두 서비스는 각자 자기 데이터를 소유하고, 남의 데이터를
  복제해 읽을 일이 없다. 프로젝션이 필요해지는 것은 하류 소비자(정산 등)가 생길 때다.

---

## 3. 디자인 패턴 (Design Patterns)

| 패턴 | 어디에 | 왜 |
| --- | --- | --- |
| **Transactional Outbox** (ADR 0003) | `shared-common/common/outbox` | 도메인 커밋과 이벤트 발행이 한 트랜잭션 안에 있어야 "저장은 됐는데 이벤트가 안 나갔다"가 불가능해진다 |
| **3단 멱등 방어** | outbox `event_id` UNIQUE → 컨슈머 `processed_events` PK → 도메인 UNIQUE | at-least-once 브로커 위에서 정확히 한 번의 효과를 만든다 |
| **이벤트 계약-as-code** (ADR 0024) | `shared-common/src/testFixtures/.../contracts/events` | 스키마 드리프트를 런타임이 아니라 빌드 시점에 깬다 |
| **토픽 카탈로그** (ADR 0035) | `shared-common/src/main/resources/kafka/topic-catalog.json` | 파티션 수 변경 = 키 재해시 = 순서 보장 소급 붕괴. 되돌릴 수 없어서 정본이 필요하다 |
| **상태머신 as 도메인 규칙** | `OrderStatus.canTransitionTo()` 등 | 전이표가 enum 안에 선언적으로 산다. 서비스 계층이 if 로 흉내 내지 않는다 |
| **원장(Ledger)** | `point/`, `giftcard/` | 잔액은 계산 결과지 저장값이 아니다. 로트 단위 FEFO 소비 + 환불 시 로트 복원 |
| **DLT + replay** (ADR 0017) | 공용 `KafkaConsumerErrorHandlingConfig` | 재시도 소진 메시지를 조용히 skip 하지 않는다(= 유실) |
| **ShedLock** | `@SchedulerLock` | shedlock 테이블을 가진 모듈 = 다중 인스턴스 전제. 락 없는 `@Scheduled` 는 파드 수만큼 중복 실행된다 |
| **다중 PG 라우팅 + Bulkhead** (ADR 0010) | `payment/adapter/out/pg` | PG 한 곳의 장애가 결제 전체를 멈추지 않게 |
| **메타 주도 게시판** | `operation/board` | 정의 1행 = 게시판 1개. 프론트 단일 라우트가 스킨으로 렌더 — 게시판을 늘려도 라우트가 늘지 않는다 |

---

## 4. 기술 스택 (Tech Stack)

| 구분 | 기술 |
| --- | --- |
| 언어 / 런타임 | **Java 25** (toolchain 고정) |
| 프레임워크 | **Spring Boot 4.0.7** · Spring Security · Spring Data JPA · Spring Kafka · Spring Batch |
| Gateway | Spring Cloud Gateway 2025 (WebFlux) |
| 빌드 | Gradle (Kotlin DSL) 멀티모듈 + composite build |
| DB | PostgreSQL 17 · Flyway · QueryDSL · pgbouncer |
| 검색 | Elasticsearch 8.17 |
| 메시지 | Kafka (Redpanda) |
| 캐시 | Caffeine(L1) + Redis(L2, 선택) — Pub/Sub 무효화 |
| PG | Toss Payments |
| 회복탄력성 / RateLimit | Resilience4j / Bucket4j |
| 관측 | Micrometer + Prometheus + Grafana + OTLP(Tempo) |
| PDF | iText 8 |
| 프론트 | React 19 · TypeScript · Vite · Vitest · Playwright(E2E) |
| 테스트 | JUnit 5 · AssertJ · Mockito · ArchUnit · Testcontainers |

---

## 5. CI/CD 파이프라인

```
push/PR  →  ci.yml
             ├─ changes        변경 경로 감지(dorny/paths-filter) → 모듈 매트릭스 계산
             ├─ backend-ci     모듈별 build + test + JaCoCo(LINE 90%) + SonarCloud + SBOM/Trivy
             ├─ frontend-ci    typecheck + lint + vitest(커버리지 임계 90) + production build
             ├─ backend-ghcr   변경된 서비스만 이미지 빌드 → GHCR
             └─ frontend-ghcr  프론트 이미지 → GHCR
         →  harness-guard.yml  변경 파일 가드 + 삭제 가드 + 매니페스트 추적 + 설정 고아 파라미터
         →  semgrep.yml        SAST
```

- 이미지 이름은 `github.repository` 에서 파생된다 — `ghcr.io/<owner>/shop`(order) ·
  `…-gateway` · `…-operation`.
- **`cancelled` 는 통과가 아니다.** develop 은 최신 커밋이 이기므로 중간 커밋의 실행이 취소되는데
  빨간 X 가 남지 않는다. 판정 유무는 눈이 아니라 `node scripts/harness/ci-verdict.mjs [sha]` 로
  체크 단위 확인한다(취소·스킵·진행중은 결론으로 세지 않는다).

---

## 6. 진화 (Evolution)

```
단일 모놀리스
   → Bounded Context 분리
   → 이벤트 드리븐(Outbox + Kafka)
   → DB-per-service
   → 슬라이스 흡수(프로세스는 줄이고 경계는 테스트로 남긴다 — ADR 0041~0043)
```

마지막 단계가 이 저장소의 성격을 결정한다. **"서비스를 나누는 것"이 아니라 "경계를 지키는 것"이
목적이므로**, 프로세스를 합쳐도 경계는 ArchUnit 슬라이스 테스트로 그대로 살아 있다.
프로세스가 줄면 배포·운영 비용이 줄고, 경계가 남으면 필요할 때 다시 떼어 낼 수 있다.
