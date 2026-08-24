# PRD — 조직·멤버십 (order-service 의 organization 슬라이스)

> **2026-08-25 — 독립 서비스에서 order 슬라이스로 이관**([ADR 0042](../../adr/0042-organization-absorbed-into-order.md)).
> 이전 경계: `organization-service`(8104, 자체 DB `lemuel_organization`). 현재: order-service 의
> `github.lms.lemuel.organization` 슬라이스, 스키마 `opslab`(`opslab.organizations`·`opslab.memberships`).
> **외부 계약은 불변**이다 — 경로 `/api/organizations/**`, 4개 발행 토픽명, 페이로드 모두 그대로다.
> 아래 본문의 "서비스" 서술은 그 슬라이스를 가리킨다.

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 자매 문서 `finance-card-slice.md`·`settlement-core.md` 와 같은 규약을 쓴다 —
> 새 기능을 제안하지 않고, 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목      | 값                                                                                     |
> | --------- | ---------------------------------------------------------------------------------------- |
> | 대상 범위 | `organization-service`(8104, DB `lemuel_organization`) 전체 — 셀러/기업 조직과 멤버십   |
> | 역산 기준 | 2026-08-13 `develop` 브랜치                                                            |
> | 근거      | 도메인 9개 클래스, 진입 어댑터 1종(REST), 발행 4토픽, 테스트 12개 클래스                |
> | 범위 밖   | 권한 판정(소비측 몫) · 조직 단위 정산 배분 · 사용자 계정 자체(order-service `users`)    |
> | 관련 문서 | [`../../../SPEC.md`](../../../SPEC.md) · `order-service-organization-membership.seed.yaml` · `card-service-rules`(소비측) |

---

## 1. 배경과 문제

플랫폼의 사용자는 개인 계정이다. 그런데 **셀러는 대개 혼자가 아니다** — 대표가 있고 정산 담당자가 있고
현장 직원이 있다. 법인카드·문서함·정산 조회를 "이 계정의 소유자만" 으로 묶으면 세 가지가 막힌다.

| 문제              | 구체적 손상                                                                 |
| ----------------- | --------------------------------------------------------------------------- |
| **계정 공유**     | 담당자가 대표 계정을 같이 쓴다. 누가 무엇을 했는지 감사할 수 없다           |
| **권한 전부 아니면 전무** | 직원에게 카드를 주려면 정산 조회까지 열어야 한다                     |
| **인수인계 공백** | 대표가 바뀌면 조직의 자산(카드·문서)이 누구에게 귀속되는지 규칙이 없다      |

organization-service 는 **조직이라는 1급 개념**을 세우고 사용자를 역할로 묶는다. 핵심 설계 판단은 하나다 —
**소유권 구조만 정의하고, 그 구조로 무엇을 할 수 있는지는 소비측이 정한다.**

## 2. 목표 / 비목표

### 2.1 목표

| # | 목표 | 성공 기준 |
|---|---|---|
| G1 | 조직에 여러 사용자가 역할로 소속된다 | OWNER/MANAGER/STAFF 3역할, 초대→수락 흐름 |
| G2 | 조직이 소유자를 잃지 않는다 | 마지막 OWNER 는 제거·강등 불가 |
| G3 | 소비 서비스가 조직 상태를 알 수 있다 | 4토픽 발행으로 프로젝션 구축 가능 |
| G4 | 상태 변화가 임의로 일어나지 않는다 | 상태머신이 전이를 강제 |

### 2.2 비목표

| # | 비목표 | 이유 |
|---|---|---|
| N1 | **권한 판정** | 역할이 무엇을 할 수 있는지는 자원 소유 서비스(card 등)가 정한다 |
| N2 | 사용자 계정 관리 | 계정은 order-service `users` 소관. 여기는 소속만 다룬다 |
| N3 | 조직 단위 정산 배분 | 정산은 셀러 단위 — 조직 분할은 설계 밖 |
| N4 | 조직 상태와 멤버십 상태의 연동 | 정지된 조직의 멤버가 무엇을 할지는 소비측 판단(→ G-3) |

## 3. 사용자

| 사용자 | 무엇을 위해 쓰는가 |
|---|---|
| **셀러 대표(OWNER)** | 조직 생성, 구성원 초대·역할 변경·제거 |
| **정산 담당자(MANAGER)** | 위임받은 범위의 업무 수행(범위는 소비측이 정의) |
| **직원(STAFF)** | 조직 소속으로 제한된 자원 사용(예: 법인 서브카드) |
| **소비 서비스**(card-service) | 조직·멤버십 프로젝션을 만들어 자기 권한 판정에 사용 |

## 4. 제품 범위 — 기능 맵

| 영역 | 기능 |
|---|---|
| 조직 | 생성(자동 OWNER 부여), 조회, 상태(ACTIVE⇄SUSPENDED) |
| 멤버십 | 초대 → 수락, 역할 변경, 제거, 정지·복귀 |
| 역할 | OWNER(3) > MANAGER(2) > STAFF(1) 위계 |
| 이벤트 | 생성·가입·역할변경·제거 4토픽 발행 |
| 보호 | 마지막 OWNER 방어, 전이 강제 |

## 5. 핵심 유스케이스

### UC-1. 조직을 만들면 만든 사람이 OWNER 가 된다

1. 사용자가 `POST /api/organizations` 로 조직을 만든다.
2. **같은 트랜잭션에서** 생성자의 OWNER 멤버십이 만들어진다 — 상태는 즉시 `ACTIVE`, `invitedBy` 는 self.
3. `OrganizationCreated` 가 발행된다.

> 조직이 소유자 없이 존재하는 순간이 없다. 이것이 G2(소유자 상실 방지)의 출발점이다.

### UC-2. 구성원을 초대하고 수락받는다

1. OWNER/MANAGER 가 `POST /{orgId}/members` 로 초대한다 → 멤버십 상태 `INVITED`.
2. 초대받은 사용자가 `POST /{orgId}/members/accept` 로 수락 → `ACTIVE`, `OrganizationMemberJoined` 발행.
3. 수락 전에는 조직 자원에 접근할 수 없다(접근 판정은 소비측이지만, 상태가 근거를 제공한다).

### UC-3. 마지막 OWNER 는 사라질 수 없다

1. OWNER 를 제거하거나 STAFF 로 강등하려 한다.
2. 서비스가 **조직 전체의 활성 OWNER 수**를 세어 1명뿐이면 `LastOwnerException` 으로 거부한다.
3. 정지(SUSPENDED)된 OWNER 는 **정족수에 포함되지 않는다** — 실제로 조직을 운영할 수 있는 사람만 센다.

> 이 검사가 도메인이 아니라 서비스 계층에 있는 이유: 마지막 OWNER 여부는 **여러 멤버십을 가로질러야**
> 알 수 있고, 단일 멤버십 애그리거트는 그 사실을 모른다.

### UC-4. 소비 서비스가 조직 구조를 반영한다

1. card-service 가 4토픽을 구독해 자기 DB 에 조직 프로젝션을 적재한다.
2. 카드 발급·서브한도 배정 시 그 프로젝션으로 소속·역할을 확인한다.
3. organization-service 는 card 를 모른다 — 단방향이다.

## 6. 기능 요구사항

| FR | 요구사항 | 강제 지점 |
|---|---|---|
| FR-1 | 조직 상태는 ACTIVE⇄SUSPENDED 뿐이다 | `OrganizationStatus` |
| FR-2 | 멤버십 상태는 INVITED→ACTIVE→SUSPENDED⇄ACTIVE, REMOVED 종단 | `MembershipStatus` |
| FR-3 | 비정상 전이는 타입 예외로 거부한다 | `InvalidOrganizationTransitionException`·`InvalidMembershipTransitionException` |
| FR-4 | 역할은 위계 수치를 갖는다 | `OrgRole` OWNER(3)/MANAGER(2)/STAFF(1) |
| FR-5 | 조직 생성 시 생성자 OWNER 멤버십을 함께 만든다 | 생성 서비스 |
| FR-6 | 마지막 활성 OWNER 는 제거·강등 불가 | `LastOwnerException` |
| FR-7 | 정족수는 "활성이면서 OWNER" 만 센다 | `Membership.isActiveOwner` |
| FR-8 | REMOVED 멤버십은 역할 변경 불가 | 도메인 검증 |
| FR-9 | 4토픽을 Outbox 로 발행한다 | `OrganizationEventPublisherAdapter` |

## 7. 도메인 규칙 (BR)

| BR | 규칙 | 근거 |
|---|---|---|
| BR-1 | **조직은 항상 소유자를 갖는다** — 생성 시 자동 부여 + 마지막 OWNER 보호 | 생성 서비스 + `LastOwnerException` |
| BR-2 | **가로지르는 불변식은 서비스 계층** — 단일 애그리거트가 알 수 없는 사실은 서비스가 강제한다 | `Membership` 주석 |
| BR-3 | **정족수는 실효 인원 기준** — 정지된 OWNER 는 소유자로 세지 않는다 | `isActiveOwner` |
| BR-4 | **권한은 여기서 정하지 않는다** — 역할은 사실이고, 그 사실로 무엇을 허용할지는 자원 소유자가 정한다 | 비목표 N1 |
| BR-5 | **발행 전용** — 소비 컨슈머가 없다. 이 서비스는 조직 사실의 상류다 | 컨슈머 0건 |

## 8. 데이터 모델

| 테이블 | 역할 | 특기 |
|---|---|---|
| `organizations` | 조직 | 타입(SELLER 등)·상태 |
| `memberships` | 소속 | (조직, 사용자) 관계 + 역할 + 상태 + `invited_by` |
| `outbox_events` | 발행 | Outbox 패턴 |
| `audit_logs` | 감사 | 파티션드 + 런웨이 러너 |

## 9. 인터페이스

### 9.1 REST (JWT 필요)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/organizations` | 조직 생성(자동 OWNER) |
| GET | `/api/organizations/{orgId}` | 조직 조회 |
| POST | `/api/organizations/{orgId}/members` | 초대 |
| POST | `/api/organizations/{orgId}/members/accept` | 수락 |
| PATCH | `/api/organizations/{orgId}/members/{userId}/role` | 역할 변경 |
| DELETE | `/api/organizations/{orgId}/members/{userId}` | 제거 |

### 9.2 이벤트

| 이벤트 | 트리거 | 소비처 |
|---|---|---|
| `OrganizationCreated` | 조직 생성 | card-service |
| `OrganizationMemberJoined` | 초대 수락 | card-service |
| `OrganizationMemberRoleChanged` | 역할 변경 | card-service |
| `OrganizationMemberRemoved` | 멤버 제거 | card-service |

**소비 0** — 상류 서비스다.

## 10. 비기능 요구

| NFR | 요구 | 현재 상태 |
|---|---|---|
| NFR-1 | 커버리지 LINE ≥ 90% | JaCoCo 게이트 |
| NFR-2 | 헥사고날 의존 방향 | `OrganizationArchitectureTest` |
| NFR-3 | 인바운드 포트 도달성 | `InboundPortReachabilityTest` |
| NFR-4 | 이벤트 계약 드리프트 차단 | `OrganizationEventContractTest` |

## 11. 배치 (Asia/Seoul)

| 시각 | 작업 |
|---|---|
| 주기 | 감사 로그 파티션 런웨이(`PartitionMaintenanceRunner`) |

> 도메인 배치는 없다 — 조직 상태는 사용자 조작으로만 변한다.

## 12. 역산에서 드러난 격차

### G-1. 전용 규칙 스킬이 없다

16서비스 중 organization·insurance·deposit 3곳만 `*-rules` 스킬과 skill-router 배선이 없다.
`../../../HARNESS.md` 가 "알려진 부채"로 명시하지만, 그 결과 **이 서비스를 고치는 사람에게 자동으로 로드되는
도메인 규칙 문서가 없다.** 현재는 이 PRD 와 Seed 가 그 자리를 대신한다.

### G-2. 마지막 OWNER 보호의 동시성이 미검증이다

BR-2 대로 검사가 서비스 계층에 있다. 그런데 **두 OWNER 가 동시에 서로를 제거**하는 요청이 들어오면
각각 "아직 OWNER 가 2명"으로 읽고 둘 다 통과할 수 있다. 락 전략(비관적 락·유니크 제약)이 있는지는
역산 범위에서 확인하지 않았다. 성공하면 **소유자 없는 조직**이 생긴다.

### G-3. 조직 상태와 멤버십 상태가 연동되지 않는다

조직이 `SUSPENDED` 여도 멤버십은 `ACTIVE` 로 남는다. 정지된 조직의 멤버가 카드를 쓸 수 있는지는
**소비측 판단**에 맡겨져 있는데, 소비측이 조직 상태까지 확인하는지는 이 서비스가 알 수 없다.
비목표 N4 로 선언했지만 실제 사고 가능성은 남는다.

### G-4. 소비처가 하나뿐이다

4토픽을 받는 곳이 card-service 뿐이다. 조직 변경이 **정산 권한·문서함 접근에는 반영되지 않는다.**
이것이 의도된 범위인지(정산은 셀러 단위라 조직과 무관) 미배선인지가 문서에 없다.

### G-5. 초대의 수명이 없다

`INVITED` 상태에 만료 개념이 보이지 않는다. 수락되지 않은 초대가 영구히 남으면, 퇴사자가 나중에
수락해 조직에 들어올 수 있다.

## 13. 추적 항목

| # | 항목 | 상태 |
|---|---|---|
| T-1 | `organization-rules` 스킬 신설 | 미착수 (G-1) |
| T-2 | 마지막 OWNER 동시성 방어 확인 | 미확인 (G-2) |
| T-3 | 조직 정지의 소비측 반영 규칙 합의 | 문서 없음 (G-3) |
| T-4 | 초대 만료 정책 | 없음 (G-5) |
