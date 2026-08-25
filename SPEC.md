# Shop 기능명세서 (Functional Specification)

이커머스 쇼핑몰 MSA 플랫폼의 전체 기능 명세. **2개 마이크로서비스 + API Gateway** 로 구성된
헥사고날 백엔드다.

- 문서 상태: 현행 코드 기준 요약 명세 (엔드포인트 표면 + 도메인 규칙 + 이벤트 흐름)
- 범위: **백엔드 표면**이다. 프론트 화면(라우트·메뉴)은 이 문서가 아니라 `menus` 시드 +
  `frontend/src/App.tsx` 가 정본이고, 화면↔API 대응은 `api-screen-gate.test.mjs` 가 기계로 강제한다.

---

## 1. 개요

| 항목           | 내용                                                                                                          |
| -------------- | ------------------------------------------------------------------------------------------------------------- |
| 도메인         | 회원·상품·장바구니·주문·결제(포인트·기프트카드 원장 포함)·쿠폰·리뷰·배송·대량주문·셀러등급·조직/멤버십 + 운영관제·알림·게시판·교육 |
| 서비스 수      | **3개** — order-service · operation-service · gateway-service                                                 |
| 아키텍처       | 헥사고날(Ports & Adapters), DB-per-service, 이벤트 드리븐(Outbox + Kafka)                                     |
| 서비스 간 연계 | **Kafka 이벤트로만** (코드·DB 직접 의존 0)                                                                    |

### 기술 스택

| 구분                          | 기술                                                                          |
| ----------------------------- | ------------------------------------------------------------------------------- |
| 언어 / 프레임워크             | **Java 25 / Spring Boot 4.0.7**                                               |
| 빌드                          | Gradle 멀티모듈 (Kotlin DSL), shared-common 은 composite build                 |
| Gateway                       | Spring Cloud Gateway 2025 (WebFlux)                                            |
| DB / 검색                     | PostgreSQL 17 (DB-per-service) / Elasticsearch 8.17                            |
| 메시지                        | Kafka (Redpanda 호환)                                                          |
| PG 연동                       | Toss Payments                                                                  |
| 배치 / 캐시                   | Spring Batch / Caffeine(L1) + 선택 Redis(L2)                                   |
| PDF / 마이그레이션            | iText 8 / Flyway                                                               |
| 관측 / 회복탄력성 / RateLimit | Micrometer+Prometheus+OTLP / Resilience4j / Bucket4j                           |

---

## 2. 횡단 관심사 (Cross-cutting)

### 2.1 인증·인가

- **인증**: JWT (HS256), `shared-common.common.config.jwt`. 발급은 order-service `AuthController`(`/auth/login`).
  토큰 클레임: subject(email), `role`, `uid`(userId). 서명 시크릿은 `JWT_SECRET`(운영 필수, 미설정 시 기동 실패).
- **역할**: `ADMIN`, `MANAGER`, `USER`. `anyRequest().authenticated()` 기본 + 경로별 `hasRole`/`hasAnyRole`.
- **소유권(IDOR 방지)**: 셀러 리소스는 요청 파라미터가 아니라 **JWT 주체(userId)에서 파생**하고
  조회/변경은 소유권을 대조한다(403).
- **내부 API**(`/internal/**`): `InternalApiKeyFilter`(X-Internal-Api-Key) 게이트. 키 미설정 시 기본
  통과(개발), `app.security.internal-key-required=true`(운영)면 **fail-closed** 거부.
- **RBAC 관리**(order-service `AdminRbacController`): 역할·권한 매트릭스 CRUD·복제 — 로그인 역할 위의 권한 레이어.

### 2.2 이벤트·멱등 (Outbox + Kafka)

- **Outbox 패턴**: DB 트랜잭션 안에서 `outbox_events` 에 기록 → 멀티워커 폴러(FOR UPDATE SKIP LOCKED)가 Kafka 발행.
- **3단 멱등 방어**: `outbox_events.event_id UNIQUE` → 컨슈머 `processed_events(group,event_id)` PK → 도메인 UNIQUE 제약.
- **이벤트 계약-as-code (ADR 0024)**: cross-service 56개 토픽의 JSON Schema + 정본 샘플이
  `shared-common/src/testFixtures/resources/contracts/events/` 에 단일 출처로 존재. 프로듀서·컨슈머 양방향 계약 테스트로 드리프트 차단.
- **토픽 전송 속성 (ADR 0035)**: 파티션·보존기간·순서키·소유 모듈의 정본은
  `shared-common/src/main/resources/kafka/topic-catalog.json`(등재 63건 — 계약 스키마가 붙은 것보다
  넓다: `lemuel.ops.*` 등 계약 없는 내부 토픽도 전송 속성은 카탈로그가 관리한다).
  **파티션 수 변경 = 키 재해시 = 순서 보장 소급 붕괴**라 프로비저너는 없는 토픽만 만들고 기존
  파티션은 늘리지 않는다. 신규 토픽 미등록은 `kafka-topic-gate.test.mjs` 가 CI 에서 FAIL.
- **이벤트 드리븐**: 서비스 간 연계는 Outbox → Kafka 이벤트로만 — 코드·DB 직접 의존 0
  (cross-DB 연결 0). 대사는 order 내부 API `/internal/recon` 호출로 양측이 자기 DB 만 읽는다.

### 2.3 금액·원장 안전

- 금액은 **BigDecimal** 강제, 라운딩 정책 보존. 전표는 차변1·대변1·금액1의 **구성적 균형**.
- 원장 상태: `PENDING → POSTED → REVERSED`(역분개 원칙, POSTED 불변).

### 2.4 관측·회복탄력성

- Actuator: `health,info,metrics,prometheus` 노출. `health.show-details=when-authorized`(미인증엔 상태만).
- Micrometer + Prometheus + OTLP 트레이싱. Resilience4j(회로차단), Bucket4j(rate limit).

---

## 3. 서비스별 기능 명세

### 3.1 order-service — 이커머스 거래 컨텍스트 (port 8088)

DB: opslab(로컬 `application.yml` 기본값 — **compose 는 `inter`** 다. 스키마명은 양쪽 모두 `opslab`).
회원·상품·장바구니·주문·결제(포인트·기프트카드 원장 포함)·배송 + 정합성 부속(recon, projection backfill).

| 도메인             | API(대표 경로)                                                                                                                                | 기능                                                                                |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| 회원/인증          | `/auth`, `/users`, `/memberships`                                                                                                             | 회원가입·로그인(JWT 발급)·비밀번호 재설정·멤버십 승인                               |
| 상품/카테고리/태그 | `/api/products`, `/products/{id}/variants`, `/categories`, `/admin/categories`, `/display-sections`, `/admin/display-sections`, `/admin/option-catalog`, `/api/tags`, `/admin/products/{id}/images` | 상품·SKU(variant)·이미지·카테고리 트리(계층·정렬·soft delete)·진열 섹션·옵션 카탈로그·태그. ※ `/api/categories` 매핑은 없다(gateway 라우트만 존재) |
| 장바구니/쿠폰      | `/users/{userId}/cart`, `/coupons`                                                                                                            | 장바구니, 쿠폰 발급/사용(등급별 권한)                                               |
| 주문               | `/orders`, `/orders/{id}/shipment`, `/api/bulk-orders`, `/admin/shipments`, `/admin/shipping-policies`, `/admin/payment-expiry`, `/admin/stock-reclaim` | 단건/다건(SKU 자동 재고차감) 주문, Idempotency-Key 중복제출 차단, **대량주문 초안**(CSV 멀티파트 업로드→셀 단위 검증 격자→`/{id}/confirm` 일괄 확정, `/columns` 스펙·`/revalidate`·폐기), 배송 라이프사이클·관리 콘솔·셀러 배송정책, 결제 만료·재고 회수 배치 트리거 |
| 결제               | `/payments`, `/payments/split`(+`/{id}/confirm-deposit`, `/{id}/refund`), `/api/payments/*/refunds`, `/api/payments/by-order/{orderId}/cash-receipt`, `/admin/refunds`, `/admin/pg` | Toss 결제 생성·인증·캡처·환불, **텐더 결제**(카드·계좌이체·가상계좌·포인트·기프트카드 혼합, 하한 1개), **입금 대기 창**(§아래), 현금영수증 발급·조회, PG 라우팅(서킷브레이커 상태 조회 — 설정 엔드포인트 없음), 환불이력·자동 재시도 소진 건 관리자 환불승인 |
| 포인트 원장        | `/api/points/me`, `/admin/points`                                                                                                             | 내 잔액(가용/선점/총액)·로트 조회, 관리자 수기 지급/차감·적립정책 등록/종료·만료 예정 조회·소멸 배치 수동 실행·사용한도 조회 |
| 기프트카드 원장    | `/api/gift-cards/redeem`, `/api/gift-cards/me/balance`, `/admin/gift-cards/issue`, `/admin/gift-cards/expiry/run`                             | 코드 등록(사용자 귀속)·내 잔액 조회, 관리자 발행·소멸 배치 수동 실행                |
| 리뷰/게임          | `/reviews`, `/games`, `/admin/reviews`                                                                                                        | 상품 리뷰, 게임(이벤트성), 리뷰 운영 콘솔(숨김·복원·상태 집계·CSV)                  |
| 시스템 관리        | `/api/menus`, `/admin/menus`, `/admin/common-codes`, `/admin/rbac`, `/admin/seller-tiers`, `/admin/members`, `/admin/coupons`, `/admin/audit-logs` | 메뉴 조회(`/api/menus/me`)·트리·순환참조 방지·배치정렬, 공통코드 그룹/항목, RBAC 역할·권한, 셀러 등급 콘솔(ADR 0031), 회원 콘솔(역할 변경·상태 집계·CSV), 쿠폰 콘솔(활성/비활성·사용이력·CSV), 감사로그 조회(행위 집계·CSV) |

**포인트 원장**(`point/`, 설계 정본 [`docs/plan/point-ledger.md`](docs/plan/point-ledger.md)) — `TenderType.POINT`
가 잔액 없이 열려 있던 회계 구멍을 닫은 도메인. **로트(lot) 단위 append-only 원장**이다.

- 소비 순서는 `expires_at ASC NULLS LAST, id ASC`(**만료 임박분 우선**) — 출처(origin)로 우선순위를 주지 않는다.
- **환불 복원**: 원 로트가 `ACTIVE`·`EXHAUSTED` 면 그 로트를 되살려 **원래 유효기간을 유지**하고,
  `EXPIRED`·`REVOKED` 면 `REFUND_RESTORE` 출처로 신규 로트를 발급하되 **원 로트가 가졌던 기간 길이**를 승계한다.
- 복원 대상 계정은 요청이 아니라 **원 사용 엔트리에서 도출**한다(`RestorePointCommand` 에 userId 가 없다 — IDOR 차단).
  반대로 사용(use)의 주체는 JWT 에서 파생한다.
- **선점(hold)**: 가상계좌·무통장처럼 입금 대기가 걸린 결제는 차감이 아니라 선점이다.
  `hold` 는 available−X/locked+X 로 **총액을 바꾸지 않고 로트도 건드리지 않는다**(엔트리도 남기지 않는다 —
  감사 흔적은 `point_holds` 자신이 진다). 3자 대조 축이 `available` 이 아니라 **`total`** 인 이유다.
- 입금 확정과 만료 배치의 경합은 **계정 행 비관적 락 + 선점의 종단 전이 가드**로 막는다.
  선점이 없을 때 확정은 예외(고객 포인트를 받지 않고 주문이 확정된다), 해제는 경고 후 통과(막으면 만료 배치가 함께 멈춘다).
- 멱등 최종 방어선은 `uq_point_entries_natural` — 같은 tender 의 중복 차감/복원이 DB 에서 막힌다.

**기프트카드 원장**(`giftcard/`, 정본 [`docs/plan/gift-card-ledger.md`](docs/plan/gift-card-ledger.md)) —
포인트와 원장 패턴은 같고 **다른 것만** 둔다.

- 잔액은 계정이 아니라 **증서(카드) 하나**에 붙는다(요약 테이블 없음). 부분 사용 허용(잔액 이월), 소멸은 카드 단위.
- **코드가 곧 재산**: `code_hash`(SHA-256 UNIQUE)만 저장하고 표시는 `code_last4`. 코드는 발행 응답에서 **한 번만**
  나가고 이후 재조회 불가. `GC-` + Crockford Base32 16자 ≈ 80비트. 등록 경로에 `RateLimitFilter`
  (`giftcard-redeem`) — 실패한 등록 시도는 코드를 로그에 남기지 않는다.
- **등록은 1회뿐**이다(등록된 카드를 다른 사용자가 다시 등록하면 코드를 아는 사람이 남의 잔액을 가져간다).
- 포인트 로트 재사용을 채택하지 않은 이유는 회계다 — 두 부채를 한 계정에 뭉치면 분리 표시가 불가능하다.
- **선점 수단이 없다**(Phase 2 는 포인트만) → `기프트카드 + 가상계좌` 조합은 PG 호출 전에 거절한다.

**입금 대기 창**(가상계좌·무통장) — 텐더 하나라도 입금 대기면 **승인까지만** 하고 캡처하지 않는다(카드 텐더도
마찬가지 — 입금이 끝내 안 오면 카드만 먼저 매입해 둔 것을 환불로 되돌려야 한다). 결제는 `READY` 로 남아
미입금 만료 배치가 집어갈 수 있고, 주문 `PAID` 전이도 `payment.captured` 발행도 **입금 확인 시점에만** 일어난다.
확정 진입점 `POST /payments/split/{paymentId}/confirm-deposit` 은 웹훅 중복 통보가 정상이라 **멱등이 기능의 일부**다.
만료 판정의 진실원은 `paymentMethod` 문자열이 아니라 **텐더 목록**(`PaymentDomain.awaitsDeposit`).

### 3.2 operation-service — 운영 관제 (port 8092, lemuel_operation)

- `/api/ops/webhook` — Alertmanager 알람 수신(Bearer=INTERNAL_API_KEY, 상수시간 비교). key 미설정 시 프로파일 게이트.
- `/api/ops/incidents` (JWT ADMIN) — 인시던트 라이프사이클(OPEN→ACKNOWLEDGED→RESOLVED/FALSE_POSITIVE).
- `(source, correlation_key)` partial unique 로 활성 중복 0, repeat firing refire 병합.
- **신호 BC(Phase 2)**: 도메인 성공 이벤트(분모) + Prometheus 게이지 + 실패 이벤트(`lemuel.ops.*.failed`, 분자)로
  `failure_rate=signal/total` 산출. 로드맵: 베이스라인 이상탐지 → AI 브리핑.

### 3.3 organization 슬라이스 — 조직·멤버십 (order-service 안, 스키마 opslab)

셀러/기업 단위 조직과 그 구성원(멤버십)을 관리한다. `externalRef` 로 sellerId 또는 stockCode 를 느슨히 참조(비검증).

> **2026-08-25 — order-service 로 흡수**([ADR 0042](docs/adr/0042-organization-absorbed-into-order.md)).
> 독립 서비스(8104, `lemuel_organization`)에서 order 의 `organization` 슬라이스(`opslab.organizations`·
> `opslab.memberships`)로 옮겼다. **조직 마스터를 사람(user) 마스터와 같은 정체성 축에 둔다**는 것이
> 근거다 — 멤버십의 `user_id` 는 order 의 user 를 가리키는 비검증 참조였고, 서비스가 갈라져 있어
> 검증할 수 없었다. 외부 계약(경로 `/api/organizations/**`·페이로드·토픽명)은 **불변**이고,
> 게이트웨이만 order 라우트로 합류했다. 슬라이스 경계는 `OrganizationArchitectureTest` 가 강제한다
> (organization → order 의 다른 도메인 import 금지).

| 도메인 | API (base `/api/organizations`, **JWT 인증 필수**)                                                                                  | 기능                                                                                         |
| ------ | ----------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| 조직   | `POST /`, `GET /{orgId}`                                                                                                            | 조직 생성(생성자 자동 OWNER 멤버십) / 조회                                                   |
| 멤버십 | `POST /{orgId}/members`, `POST /{orgId}/members/accept`, `PATCH /{orgId}/members/{userId}/role`, `DELETE /{orgId}/members/{userId}` | 초대(OWNER/MANAGER) · 수락 · 역할 변경(OWNER 전용) · 제거(OWNER 전용, **마지막 OWNER 보호**) |

- **타입/역할**: `OrganizationType`=SELLER·CORPORATE, `OrgRole`=OWNER>MANAGER>STAFF. 인가는 요청 파라미터가 아니라
  JWT 주체(userId)의 조직 내 역할로 판정(IDOR 방지, `OrgAuthorizer`).
- **상태머신**: Organization ACTIVE⇄SUSPENDED. Membership INVITED→ACTIVE⇄SUSPENDED, 각 상태→REMOVED(터미널).
- **이벤트 발행**(Outbox, `aggregateType="Organization"`): `lemuel.organization.created`, `lemuel.organization.member_joined`,
  `lemuel.organization.member_role_changed`, `lemuel.organization.member_removed`.
  4종 모두 **발행 전용**이다 — 소비자는 이 저장소 밖에 있다(컨슈머 0 이 이 슬라이스의 설계다).
  shared-common 의존(JWT·Outbox·멱등컨슈머).

### 3.4 board 슬라이스 — 메타 주도 게시판 (operation-service 内, port 8092, lemuel_operation · ADR 0043)

> **2026-08-25 — operation-service 로 흡수**(ADR 0043). 독립 서비스(8114/mgmt 8115, `lemuel_board`)에서
> operation 의 `board` 슬라이스로 옮겼다. **REST 경로(`/api/boards/**`·`/admin/boards/**`)와 권한 모델은
> 불변**이고 게이트웨이만 operation 라우트로 합류했다. 발행 0·소비 0 도 그대로다. 슬라이스 경계는
> `BoardArchitectureTest` 가 강제한다.

게시판을 **코드가 아니라 데이터로** 만든다. `board_definitions` 1행 = 게시판 1개이고, 프론트의 단일
라우트 `/boards/:boardKey` 가 정의를 읽어 스킨을 바꿔 그린다 — 게시판을 늘리는 데 배포도 마이그레이션도
필요 없다. "CRUD 게시판"과 "이미지 게시판"은 별개 도메인이 아니라 같은 도메인의 두 스킨이다.
설계 근거 정본: [`docs/plan/board-service.md`](docs/plan/board-service.md).

| 도메인    | API                                                                     | 기능                                              |
| --------- | ----------------------------------------------------------------------- | ------------------------------------------------- |
| 이용      | `GET /api/boards` · `GET /api/boards/{boardKey}`                        | 활성 + 호출자가 읽을 수 있는 게시판 정의 조회     |
| 관리 콘솔 | `GET|POST /admin/boards` · `PUT|DELETE /admin/boards/{id}` · `POST /admin/boards/{id}/{activate,deactivate}` (ADMIN) | 게시판 생성 · 정책 수정 · 개폐 · 삭제 |
| 게시글    | `GET /api/boards/{key}/posts`(페이지·분류·검색) · `GET|POST /api/boards/{key}/posts` · `PUT|DELETE .../{postId}` · `POST .../{postId}/{pin,hide,restore}` | 목록(고정 먼저·최신순, 본문 미포함) · 상세(조회수 증가) · 작성 · 수정 · 삭제(상태 전이) · 운영 조작 |
| 댓글      | `GET|POST /api/boards/{key}/posts/{postId}/comments` · `DELETE /api/boards/{key}/comments/{commentId}` | 목록(삭제분은 자리표시) · 작성(답글 1단) · 삭제 |
| 첨부      | `GET|POST /api/boards/{key}/posts/{postId}/attachments`(멀티파트) · `GET /api/boards/{key}/attachments/{id}/download` · `DELETE .../attachments/{id}` | 목록 · 업로드(매직바이트 판정) · 다운로드 · 삭제 |

- **스킨 4종**: `LIST`(공지·자료실) · `GALLERY`(이미지 게시판) · `FAQ`(아코디언) · `QNA`(질문·답변).
  스킨은 정책을 강제한다 — `GALLERY` 는 첨부를, `QNA` 는 댓글을 끌 수 없다(도메인 조립 시점 차단).
- **인가는 역할 allowlist**(`read/write/comment/manage_roles`). RBAC `permissions` 코드로 판정하지 않는다 —
  그 테이블은 order DB 라 읽는 순간 DB-per-service 경계가 무너진다. **읽기가 비면 공개 게시판**(비로그인 포함),
  쓰기·댓글·운영은 비울 수 없다(익명 쓰기 미지원).
- **읽을 수 없는 게시판은 403 이 아니라 404** — 403 은 존재를 알려 줘 키 대입으로 비공개 게시판을 훑게 한다.
- **발행 0 · 소비 0**: Kafka 토픽이 없다. 권한은 역할, 작성자명은 작성 시점 스냅샷, 분류는 order 공통코드 그룹
  **코드 문자열 참조**(cross-DB FK 아님)라 어떤 외부 조회도 필요 없다.
- **메뉴 등록은 별도 조작**: `menus` 는 order-service 소유다. 관리 화면이 게시판 생성 후 기존
  `POST /admin/menus` 를 한 번 더 호출하고, 연결 상태는 두 API 응답을 화면에서 대조해 배지로 보여 준다.
  게시판 생성이 곧 전사 네비게이션 변경이 되면 테스트 게시판·오타 난 이름이 즉시 모두에게 노출된다.
- **키(`boardKey`)는 불변**(URL·메뉴 행이 가리킨다), **삭제는 닫힌 게시판만**(운영 중 삭제는 되돌릴 수 없다).
- 스캔 범위를 board 패키지로 한정해 shared-common Outbox·Audit 엔티티를 끌어오지 않는다 —
  쓰지 않는 `outbox_events` 를 만들어 두면 다음 사람이 이 서비스가 이벤트를 발행한다고 오해한다.
- **인가는 도메인이 판정한다**: `BoardPost.edit(actor, ...)` 처럼 애그리거트가 주체를 받아 소유권을 대조한다.
  컨트롤러에 두면 어댑터를 하나 더 만들 때 조용히 빠지고 그게 IDOR 이 된다. 주체는 JWT 에서만 만든다.
- **작성자 표시명은 마스킹 스냅샷**(`ad***`) — 원문 이메일을 board DB 에 저장하지 않는다(PII 확산 차단).
  소유권 대조는 `author_id` 로 하므로 인가 정확도는 그대로다.
- **삭제는 상태 전이**(글·댓글 모두). 삭제된 댓글은 원문 대신 `삭제된 댓글입니다.` 자리표시만 응답에 나간다
  — 원문은 신고·감사 대응을 위해 DB 에만 남는다. 숨김(HIDDEN, 운영자가 되돌릴 수 있음)과 삭제(작성자 의사)를 가른다.
- **가시성은 질의 조건으로 번역**한다(`PostSearchCriteria`) — 페이지를 읽고 자바에서 걸러 내면
  총건수와 페이지 크기가 어긋난다. 동적 조건은 Specification 으로 만든다(`:param IS NULL OR` JPQL 은 PG bytea 트랩).
- **HTML 본문은 저장 시점에 정화**한다(`SanitizeHtmlPort` ← jsoup `Safelist.relaxed()` 화이트리스트).
  작성·수정 두 경로가 모두 `BoardContentSanitizer` 를 지난다 — 한쪽만 막으면 수정으로 심는 우회가 남는다.
  MARKDOWN·댓글은 대상이 아니다(코드 블록의 정당한 태그까지 지워지고, 댓글은 HTML 렌더 경로가 없다).
- **첨부는 요청의 주장을 믿지 않는다**: 형식은 매직바이트로 판정하고(선언과 다르면 400), 저장 파일명은
  서버가 만든 UUID 이며, SVG·HTML·XML 은 정책이 허용해도 차단한다. 다운로드는 판정된 Content-Type +
  이미지만 inline + `nosniff` 3종 헤더로 나간다. 볼 수 없는 글의 첨부는 404 다(설계문서 §15).
- **Phase 3 범위**: 정의 CRUD + 게시글·댓글 + LIST/GALLERY 스킨 + sanitize + 첨부
  + HTML 본문 sanitize(Phase 3 에서 앞당김 — 사유는 설계문서 §13).
  첨부·GALLERY 스킨은 Phase 3. 그때까지 다른 스킨은 목록형으로 렌더한다.

### 3.5 education 슬라이스 — 교육 과정 관리 (operation-service 内, port 8092, lemuel_operation·스키마 `education` · ADR 0043)

> **2026-08-25 — operation-service 로 흡수**(ADR 0043). 독립 서비스(8116/mgmt 8117, `lemuel_education`)에서
> operation 의 `education` 슬라이스로 옮겼다. 테이블은 **`education` 스키마를 그대로 유지**해
> (`@Table(schema = "education")`) operation 의 `opslab` 기본 스키마와 섞이지 않는다 — 도메인 데이터
> 경계가 스키마로 남는다. 단 Outbox 는 이관하지 않고 operation 의 `opslab.outbox_events` 단일 테이블을
> 쓴다(shared-common 폴러가 그 스키마를 하드코딩한다). REST 경로·이벤트 계약은 불변이고, 슬라이스
> 경계는 `EducationArchitectureTest` 가 강제한다.

플랫폼에 붙은 셀러·FC·직원에게 내려보낼 **교육 콘텐츠**를 만들고 공개한다. 교육은 "만들자마자 공개"가
아니라 초안→차시 구성→검토→공개의 단계를 거치므로, **과정의 생애를 상태로, 차시 순서를 제약으로 강제**한다.
설계 정본: [`docs/plan/prd/education-service.md`](docs/plan/prd/education-service.md).

| 도메인 | API (base `/admin/education/courses`, **JWT `hasRole('ADMIN')`**)                                | 기능                                                       |
| ------ | ------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| 과정   | `GET /`(상태·제목 검색) · `POST /` · `GET /{id}` · `PUT /{id}` · `POST /{id}/{publish,hide,close}` | 생성(항상 DRAFT) · 조회·검색 · 수정 · 상태 전이 3종        |
| 차시   | `GET`·`POST /{courseId}/lessons` · `PUT`·`DELETE /{courseId}/lessons/{lessonId}` · `POST /{courseId}/lessons/reorder` | 목록 · 생성 · 수정 · 삭제 · 재정렬                         |

- **상태머신**: `DRAFT → PUBLISHED ⇄ HIDDEN → CLOSED`. 새 과정은 `Course.draft` 단일 팩토리로만 만들어져
  **항상 DRAFT** 다. 공개는 `DRAFT`·`HIDDEN` 에서만, 숨김은 `PUBLISHED` 에서만, 종료는 `PUBLISHED`·`HIDDEN`
  에서만. **삭제는 없다** — 공개된 적 있는 과정은 지우지 않고 `CLOSED` 로 닫는다.
- **차시는 독립 식별자를 갖되 과정 애그리거트에 속한다** — 이 서비스의 핵심 설계 판단. 그래서
  `/{courseId}/lessons/{lessonId}` 경로가 주장한 소속을 서버가 `Lesson.requireBelongsTo(courseId)` 로
  대조하고, 불일치면 404 `LESSON_NOT_IN_COURSE`. 대조를 **어댑터가 아니라 도메인에** 둔 이유는 진입점이
  늘어도 규칙이 한 곳에 남게 하기 위해서다.
- **재정렬은 전수 교체**다. 요청에 그 과정의 차시가 정확히 한 번씩 담겨야 하고(`Lesson.validateReorder`
  — 개수·중복·집합 일치), 하나라도 어긋나면 400 `LESSON_ORDER_INVALID`. **부분 재정렬은 없다.**
  저장은 2단이다 — `(course_id, sequence)` UNIQUE 때문에 두 차시를 맞바꾸면 중간 상태가 겹치므로,
  먼저 전부 음수 구간(`-1..-n`)으로 밀고 그다음 목표 순서(`1..n`)를 쓴다.
- **차시 삭제만 멱등**이다(없으면 조용히 통과). 단 존재하는데 소속이 다르면 거부한다 — 지우고 나서
  "그 과정이 아니었다"를 알면 되돌릴 수 없다.
- **콘텐츠 파일을 저장하지 않는다** — `content_ref` 로 참조만 보관(영상·문서 호스팅은 이 서비스 밖).
  **수강·진도·이수는 범위 밖**(콘텐츠 관리 전용), 역할 세분화 없음(`ADMIN` 단일).
- 모든 쓰기 유스케이스가 `education_audit_logs` 를 남기고, 과정 수정은 낙관적 락(`version`).
- **이벤트**: 공개 전이일 때만 `lemuel.education.course_published`(Outbox, `aggregateType="Education"`,
  순서키 `courseId`)를 적재한다 — 수정·숨김·종료는 발행하지 않는다. **소비 0**(§5 발행 전용).
- shared-common 은 **제한 스캔**(`scanBasePackages=...education`) — 필요한 빈만 `@Import` 한다.
  소비 측 배선은 의도적으로 들이지 않는다(education 스키마에 `processed_events` 가 없다).
- 포트는 **8116/8117** 이다. 처음엔 8115 였는데 그것이 board-service 의 management 포트와 같아
  로컬에서 둘을 동시에 `bootRun` 하면 뒤에 뜨는 쪽이 바인드에 실패했다(compose 는 컨테이너 내부가
  모두 8080 이라 드러나지 않던 충돌이다). 2026-08-23 에 8116/8117 로 옮겨 해소했다.

### 3.6 gateway-service — API Gateway (port 8080)

- Spring Cloud Gateway(WebFlux). 서비스별 경로 predicate 라우팅. 라우트는 셋뿐이다 —
  order(거래·회원·관리자 표면), operation(`/api/ops`·게시판·교육), 알림 푸시 SSE(`/api/notifications/stream`).
  `/api/organizations/**`(JWT 필수)는 order 라우트에 합류했고(ADR 0042), education 은
  `/admin/education/**`(ADMIN 전용 콘텐츠 관리라 admin 경로째 라우팅)을 라우팅.
- 자체 인증 필터 없음 — 인증·인가는 각 서비스 SecurityConfig 가 강제.
- 라우트 누락은 컴파일러도 화면 커버리지 게이트도 못 본다(서비스는 401 인데 게이트웨이는 404) —
  `gateway-route-gate.test.mjs` 가 gateway·nginx 배선을 CI 에서 강제한다.
- **알림 푸시 SSE 는 스트림 경로 하나만 노출한다** — 발송·데모(`/internal/notifications/**`)는 인증 없이
  발송하는 내부 경로라 게이트웨이에 올리지 않는다(와일드카드 금지).

### 3.7 알림 팬아웃·푸시 — operation-service 의 `notification` 슬라이스 (ADR 0041)

별도 프로세스였다가 operation-service 로 흡수됐다(ADR 0041) — 알림은 관제와 같은 성질이라
옆 슬라이스와 규율이 동형이다. **자체 저장소 없음**(무영속 — 수신함이 아니라 스트림이다).

| 슬라이스 | API / 트리거 | 기능 |
| --- | --- | --- |
| **operation `notification`** (8092) | `POST /internal/notifications/send`, `GET /internal/notifications/demo`, **`GET /api/notifications/stream`(SSE 푸시 허브 — JWT 필수, 게이트웨이가 노출하는 유일 경로)** + Kafka 리스너 | 도메인 이벤트 2토픽(`payment.captured`·`payment.refunded`) → 다채널(log/Slack/email/SSE) 알림. **가상 스레드 I/O 팬아웃 + 채널별 타임아웃(3s)/재시도(3회) 격리**, eventId 멱등(TTL 30분). 신호 컨슈머와 토픽이 겹치므로 컨슈머 그룹을 따로 둔다 — 한 그룹으로 두면 카프카가 둘을 한 소비자로 보고 파티션을 나눠 준다. Kafka 리스너는 `APP_KAFKA_ENABLED` 게이트 |

**구독 화면**: `/notifications`("내 알림", SHOP 메뉴 — `NotificationsPage`). 이 스트림을 렌더하는 유일한
화면이고, **수신함이 아니라 스트림**이다 — 서버가 알림을 저장하지 않으므로 "지난 알림 전부"를 약속하지
않는다(빈 문구가 "연결된 뒤로 도착한 알림이 없습니다"인 이유). 연결 상태 배지를 항상 그려 <b>조용히 끊긴
스트림과 "알림 없음"이 같아 보이지 않게</b> 한다. 중복 판정은 SSE `id`(서버 시퀀스)가 아니라 도메인 이벤트
UUID 로 한다 — 무영속이라 재시작하면 시퀀스가 1부터 다시 시작해, `id` 로 걸면 재시작 직후의 새 알림이
조용히 버려진다. 상세: `docs/sse.md` §2.

## 4. 도메인 상태머신·정책

```
Payment      : READY → AUTHORIZED → CAPTURED → REFUNDED  (↘ FAILED / CANCELED)
Order        : CREATED → PAID → REFUNDED/CANCELED (+ SHIPPING_PENDING·IN_TRANSIT·DELIVERED·
               CANCELLATION/REFUND 단계, OrderStatus.canTransitionTo() 강제)
Organization : ACTIVE ⇄ SUSPENDED
Membership   : INVITED → ACTIVE ⇄ SUSPENDED, 각 상태 → REMOVED(터미널)  (마지막 OWNER 불변식)
PointAccount : ACTIVE ⇄ SUSPENDED, ACTIVE|SUSPENDED → CLOSED (잔액 0 일 때만)
               ※ SUSPENDED 는 사용 불가·적립은 가능(조사 중 적립까지 막으면 정상 주문이 손해)
PointLot     : ACTIVE → EXHAUSTED(remaining=0) / → EXPIRED(소멸 배치) / → REVOKED(적립 취소)
               ※ EXPIRED·REVOKED 는 되살리지 않는다 — 되돌릴 일은 신규 로트 발급(역분개 원칙)
PointHold    : ACTIVE → CAPTURED / RELEASED / EXPIRED (종단 전이 가드가 늦게 온 쪽을 거절)
GiftCard     : ISSUED → ACTIVE → REGISTERED → {REGISTERED(부분사용), USED_UP},
               ACTIVE|REGISTERED → EXPIRED, → SUSPENDED(분실·부정)
Course(교육) : DRAFT → PUBLISHED ⇄ HIDDEN → CLOSED  (삭제 없음 — 닫을 뿐)
```

정책: 포인트 소비 순서(FEFO)·환불 복원 규칙(§3.1), 셀러 등급 산정(ADR 0031),
쿠폰 회수·멤버십 등급, 배송비 정책(셀러 기본배송비 · 무료배송 임계).

---

## 5. 이벤트 카탈로그

계약 스키마·정본 샘플: `shared-common/src/testFixtures/resources/contracts/events/` — **20개 토픽**(ADR 0024).
전송 속성(파티션·보존·순서키) 정본은 별도다 — `kafka/topic-catalog.json` 등재 **21건**(§2.2, ADR 0035).
카탈로그가 하나 더 많은 이유는 `lemuel.education.course_published` 에 계약 스키마가 없기 때문이다 —
소비자가 이 저장소 안에 생기는 시점에 ADR 0024 절차로 편입한다.

> 수치 검증: `ls shared-common/src/testFixtures/resources/contracts/events/*.schema.json | wc -l` → 20
> (`git ls-files` 로 세면 **미추적 신규 스키마가 빠져** 커밋 직후에 처음 어긋난다)

| 토픽                                                                                    | 프로듀서                     | 주요 컨슈머                                        |
| ----------------------------------------------------------------------------------------- | ---------------------------- | -------------------------------------------------- |
| `lemuel.order.created`                                                                  | order                        | operation(신호 버킷 분모 · 오늘 집계)              |
| `lemuel.payment.captured`                                                               | order                        | operation(신호 버킷 분모 · 알림 팬아웃 · 오늘 집계) |
| `lemuel.payment.refunded`                                                               | order                        | operation(알림 팬아웃 · 오늘 집계)                 |
| `lemuel.user.registered`                                                                | order                        | operation(오늘 집계 — 2026-08-25 편입)             |
| `lemuel.product.changed`                                                                | order                        | 발행 전용 — 소비자는 이 저장소 밖                  |
| `lemuel.seller.tier_changed`                                                            | order                        | 발행 전용. `reason=BACKFILL` 은 변경이 아니라 초기 적재용 재발행(ADR 0031) |
| `lemuel.point.charged` / `.granted` / `.used` / `.restored` / `.expired` / `.revoked`   | order                        | 발행 전용 — 포인트 부채 GL 소비자는 이 저장소 밖. 순서키 `accountId` |
| `lemuel.giftcard.registered` / `.used` / `.restored` / `.expired`                       | order                        | 발행 전용 — 상품권 부채 GL 소비자는 이 저장소 밖. 순서키 `giftCardId` |
| `lemuel.organization.created` / `.member_joined` / `.member_role_changed` / `.member_removed` | order(organization 슬라이스) | 발행 전용 — 조직 마스터 통지, 소비자는 이 저장소 밖 |
| `lemuel.education.course_published`                                                     | operation(education 슬라이스) | 발행 전용 — 과정 공개 통지                         |

부가(계약 스키마 없음): `lemuel.ops.*`(실패 신호 `*.failed` + `stock.depleted`·`stock.reclaim_delayed`·`shipping.delayed`).

발행 전용(소비처 미배선 — 의도된 상태, 소비자가 생기면 ADR 0024 절차로 계약 편입).
**이 목록은 `topic-consumer-gate.test.mjs` 가 기계로 강제한다** — 카탈로그 토픽 중 어떤 서비스도
구독하지 않는 것이 그 게이트의 `PUBLISH_ONLY` 에 없으면 CI 가 FAIL 한다.

> "발행 전용"이 여기서 뜻하는 것은 **소비자를 안 만든 것이 아니라 경계 밖에 있다**는 것이다.
> 이 저장소는 커머스 코어와 운영만 담고, 정산·여신·계정계 같은 하류 소비자는 밖에 있다.
> 소비자가 이 저장소 안에 생기면 그때 컨슈머 계약 테스트를 붙이는 것으로 끝난다.
>
> 실제로 그 경로를 한 번 밟았다 — `lemuel.user.registered` 는 2026-08-25 "오늘 한눈에" 집계
> 컨슈머(`BusinessEventDashboardConsumer`)가 생기면서 발행 전용에서 빠졌다. 스키마는 이미
> 있었으므로 편입 비용은 **정본 샘플을 실제 컨슈머에 통과시키는 테스트 한 건**이었다
> (`BusinessEventDashboardConsumerTest`). 게이트의 "죽은 항목" 검사가 목록을 지우라고 먼저 말했다.

역방향 예약: `lemuel.ops.order.failed` 는 operation 이 구독하지만 emit 지점 미배선
(OpsSignalCategory 주석 참조).

---

## 6. 비기능 요구 (Non-functional)

- **보안**: JWT(HS256, BCrypt cost=12), CORS 화이트리스트, Bucket4j rate limit, Actuator 인증,
  PII 마스킹·감사로그, 환불 동시성(Pessimistic Lock + Idempotency-Key), 내부/관리 API 키 필터(운영 fail-closed).
- **관측**: Prometheus + Micrometer + Grafana + OTLP 트레이싱, 서비스별 헬스/프로브.
- **테스트**: 도메인→서비스→컨트롤러→통합 순. JaCoCo CI 게이트 **LINE 90%**, 핵심 도메인 INSTRUCTION 80%.
  통합테스트는 Testcontainers PostgreSQL(Docker 없으면 skip).
- **배포**: Docker Compose(로컬, DB-per-service PG 2종 + ES + Redpanda + redis + pgbouncer +
  앱 컨테이너 3개 + frontend + 관측 7종), Kubernetes(운영, GitHub Actions→GHCR→ArgoCD GitOps),
  Flyway 마이그레이션.
- **운영 필수 설정**: `JWT_SECRET`(강함, ≥32바이트), `app.security.internal-key-required=true`.

---

## 7. 관련 문서

- 아키텍처·컨벤션: [`CLAUDE.md`](./CLAUDE.md) · 사용자 문서: [`README.md`](./README.md)
- 아키텍처 개요(구성·패턴·스택): [`ARCHITECTURE.md`](ARCHITECTURE.md) · 디렉토리 트리: [`STRUCTURE.md`](STRUCTURE.md)
- 서비스별 역산 PRD: [`docs/plan/prd/`](docs/plan/prd/)
- 아키텍처 결정: [`docs/adr/`](docs/adr/) (0001 헥사고날, 0003 Outbox, 0024 이벤트 계약,
  0031 셀러 등급, 0035 토픽 카탈로그, 0041~0043 흡수 결정 등)
- 도메인 규칙 스킬(`.claude/skills/*-rules` / `*-domain-rules`): order-commerce · organization ·
  board · operation-signal. **education 만 전용 스킬이 없다** — 규칙 정본은
  [`docs/plan/prd/education-service.md`](docs/plan/prd/education-service.md).
