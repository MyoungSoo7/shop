# Shop 시퀀스 다이어그램 (Sequence Diagrams)

> 쇼핑몰 MSA(2 서비스 + gateway)의 **핵심 유스케이스별 시퀀스 다이어그램**.
> 서비스 간 연계는 Kafka 이벤트로만 이루어지며(코드·DB 직접 의존 0), 비동기 구간은 `-->>` 및 `Note` 로 명시한다.
>
> - 정본 근거: [`../SPEC.md`](../SPEC.md)(기능·이벤트 카탈로그) · [`../ARCHITECTURE.md`](../ARCHITECTURE.md)(패턴) · [`adr`](adr/)

---

## 1. 참여자(Participant) 정의

| 참여자 | 설명 |
|-------|------|
| User / Seller / Admin | 구매자 · 셀러 · 운영자 |
| Gateway | gateway-service (8080, Spring Cloud Gateway — 라우팅만, 인증은 각 서비스) |
| Order | order-service (8088, opslab) — 회원·상품·주문·결제·환불·포인트·기프트카드·조직 |
| Operation | operation-service (8092, lemuel_operation) — 관제·알림·게시판·교육 |
| Kafka | Kafka(Redpanda) — 토픽 `lemuel.<domain>.<event>` |
| Outbox | 각 서비스 내 `outbox_events` + 멀티워커 폴러(FOR UPDATE SKIP LOCKED) |
| Toss | Toss Payments (외부 PG) |

---

## 2. 전체 시스템 컨텍스트 — 커머스 → 운영 이벤트 백본

```mermaid
sequenceDiagram
    actor Buyer as 구매자
    actor Seller as 셀러
    participant GW as Gateway(8080)
    participant ORD as Order(8088)
    participant K as Kafka
    participant OPS as Operation(8092)

    Buyer->>GW: 주문·결제 요청
    GW->>ORD: 라우팅 (자체 인증 없음 — 각 서비스 SecurityConfig)
    ORD->>ORD: 주문 생성 + Toss 결제 캡처 + Outbox 기록 (동일 DB tx)
    ORD-->>K: lemuel.payment.captured (Outbox 폴러, 비동기)

    par 운영 팬아웃
        K-->>OPS: 신호 버킷 분모(count_total) 적재 — 5분 버킷
    and
        K-->>OPS: 알림 팬아웃 (log/Slack/email/SSE)
    end
    OPS-->>Seller: 결제 알림 (SSE 푸시)

    Note over ORD,K: 포인트·기프트카드·조직 토픽은 **발행 전용**이다 —<br/>하류 소비자(정산·계정계)는 이 저장소 밖에 있다
```

---

## 3. 인증 플로우 — JWT 발급 (order-service AuthController)

### 3.1 정상 플로우

```mermaid
sequenceDiagram
    actor User
    participant GW as Gateway
    participant ORD as Order(AuthController)
    participant DB as opslab DB

    User->>GW: POST /auth/login (email, password)
    GW->>ORD: 라우팅
    ORD->>DB: 사용자 조회
    DB-->>ORD: 사용자(BCrypt 해시, role)
    ORD->>ORD: BCrypt(cost=12) 비밀번호 검증
    ORD->>ORD: JWT(HS256) 서명 — claims: sub(email), role, uid(userId)
    ORD-->>User: 200 AccessToken
    Note over User,ORD: 이후 모든 요청은 Authorization: Bearer 토큰.<br/>각 서비스 SecurityConfig 가 hasRole 검증 (gateway 는 라우팅만)
```

### 3.2 예외 플로우

```mermaid
sequenceDiagram
    actor User
    participant ORD as Order(AuthController)
    participant DB as opslab DB
    participant SVC as 임의 서비스

    User->>ORD: POST /auth/login (잘못된 비밀번호)
    ORD->>DB: 사용자 조회
    DB-->>ORD: 사용자
    ORD->>ORD: BCrypt 검증 실패
    ORD-->>User: 401 Unauthorized

    Note over User,SVC: 만료·위조 토큰으로 보호 API 접근
    User->>SVC: GET /api/... (만료된 JWT)
    SVC->>SVC: JWT 서명·만료 검증 실패
    SVC-->>User: 401 / 권한 부족 시 403 (IDOR: 소유권 불일치도 403)
```

---

## 4. 주문 생성 + 결제 캡처 (order-service ↔ Toss)

### 4.1 정상 플로우 — 주문 → Toss 결제 → Outbox 발행

```mermaid
sequenceDiagram
    actor Buyer as 구매자
    participant ORD as Order
    participant DB as opslab DB
    participant Toss as Toss Payments
    participant OB as Outbox 폴러
    participant K as Kafka

    Buyer->>ORD: POST /orders (Idempotency-Key)
    ORD->>DB: Idempotency-Key 중복 확인
    ORD->>DB: SKU 재고 조건부 UPDATE (원자 차감, ADR 0011)
    ORD->>DB: 주문 저장 (CREATED)
    ORD-->>Buyer: 201 주문 생성

    Buyer->>ORD: POST /payments (결제 인증·캡처)
    ORD->>Toss: 결제 승인 요청 (Resilience4j Circuit Breaker)
    Toss-->>ORD: 승인 응답
    ORD->>DB: [tx] Payment READY→AUTHORIZED→CAPTURED + Order→PAID + outbox_events INSERT
    ORD-->>Buyer: 200 결제 완료

    Note over OB,K: 비동기 — 폴러가 FOR UPDATE SKIP LOCKED 로 배치 발행 (기본 2s)
    OB-->>K: lemuel.payment.captured / lemuel.order.created
```

### 4.2 예외 플로우 — 재고 부족 · 중복 제출 · PG 실패

```mermaid
sequenceDiagram
    actor Buyer as 구매자
    participant ORD as Order
    participant DB as opslab DB
    participant Toss as Toss Payments

    Buyer->>ORD: POST /orders
    ORD->>DB: SKU 재고 조건부 UPDATE
    alt 재고 부족 (변경 행 0)
        ORD-->>Buyer: 409/422 재고 부족
    else 동일 Idempotency-Key 재제출
        ORD->>DB: 기존 주문 조회
        ORD-->>Buyer: 기존 주문 응답 (중복 생성 0)
    end

    Buyer->>ORD: POST /payments
    ORD->>Toss: 결제 승인 요청
    alt Toss 실패/타임아웃
        Toss-->>ORD: 실패 응답
        ORD->>DB: Payment→FAILED
        ORD-->>Buyer: 502/422 결제 실패
        Note over ORD: 연속 실패 시 Circuit Open — 즉시 실패로 격리
    end
```

---

## 5. 이벤트 카탈로그 참조 (다이어그램 등장 토픽 발췌)

| 토픽 | 프로듀서 | 컨슈머 | 등장 다이어그램 |
|------|---------|--------|----------------|
| `lemuel.payment.captured` / `.refunded` | order | operation(신호·알림) | §2, §4 |
| `lemuel.order.created` | order | operation(신호 분모) | §2, §4 |
| `lemuel.user.registered` | order | operation(오늘 집계 — 2026-08-25 편입) | — |
| `lemuel.product.changed` | order | 발행 전용 — 소비자는 저장소 밖 | — |
| `lemuel.point.granted` | order | marketing(보상 확정 — 2026-08-27 편입) | — |
| `lemuel.point.*` (나머지 5종) · `lemuel.giftcard.*` (4종) | order | 발행 전용 — 원장 GL 소비자는 저장소 밖 | — |
| `lemuel.marketing.reward_requested` | marketing | order(포인트 원장 적립) | — |
| `lemuel.organization.created` / `.member_joined` / `.member_removed` / `.member_role_changed` | order(organization 슬라이스) | 발행 전용 | — |
| `lemuel.education.course_published` | operation(education 슬라이스) | 발행 전용 | — |

> 계약 스키마·정본 샘플: `../shared-common/src/testFixtures/resources/contracts/events` (ADR 0024) —
> **총 26 토픽**이 계약 관리된다(검증: `contract-schema-parity-gate.test.mjs`).
> 토픽 전송 속성(파티션·보존·순서키)의 정본은 `kafka/topic-catalog.json` 이고 **25건**이 등재돼 있다 —
> 둘은 1:1 이며 그 정합을 게이트가 강제한다.
> 모든 컨슈머는 `processed_events` + 도메인 UNIQUE 로 멱등하며, **발행은 예외 없이 Outbox 를 경유한다.**
