---
name: organization-domain-rules
description: 조직/멤버십 도메인 핵심 규칙 — 발행 전용 경계(컨슈머 0, 4토픽은 card-service 프로젝션이 소비), 활성 OWNER ≥1(LastOwnerException 422), occupiesActiveSlot↔부분 UNIQUE 인덱스 동기, orderingKey=organizationId 고정, eventType 문자열=토픽 파생. order-service 의 organization 슬라이스 로직을 작성·수정·리뷰할 때 로드.
---

# 조직/멤버십 도메인 규칙 (order-service 의 organization 슬라이스)

> 2026-08-25 [ADR 0042](../../../docs/adr/0042-organization-absorbed-into-order.md) 로 독립 서비스에서
> order-service 슬라이스(`github.lms.lemuel.organization`, 스키마 `opslab`)로 이관됐다. **아래 규칙은
> 그대로 유효하다** — 특히 "발행 전용(컨슈머 0)"은 프로세스가 아니라 슬라이스의 성질이라,
> order 안에 들어왔다고 organization 이 order 의 다른 도메인을 import 하거나 컨슈머를 다는 것이
> 허용되지 않는다(`OrganizationArchitectureTest` 강제).

셀러/기업 조직·멤버십(OWNER/MANAGER/STAFF). **발행 전용 서비스** — `@KafkaListener` 0건, 타 서비스 연계는
Outbox 이벤트 발행뿐이며 4토픽을 card-service 가 조직 프로젝션으로 소비한다. 문서에 "소비처 없음"이라
쓰지 말 것(정확한 표현: "발행 전용(컨슈머 없음), 4토픽은 card-service 가 소비" — harness-audit 문서 사실 게이트가 잡는다).

## 상태머신 (도메인 봉인: private 생성자 + 팩토리, setter 0)

- `OrganizationStatus`: `ACTIVE ⇄ SUSPENDED` 두 전이뿐(자기 전이도 불가). 위반 → `InvalidOrganizationTransitionException`.
- `MembershipStatus`: `INVITED→{ACTIVE,REMOVED}` · `ACTIVE→{SUSPENDED,REMOVED}` · `SUSPENDED→{ACTIVE,REMOVED}` ·
  `REMOVED` 터미널. 위반 → `InvalidMembershipTransitionException`.
- 팩토리: `Organization.create(...)`(항상 ACTIVE 시작) / `Membership.owner(...)`(즉시 ACTIVE) /
  `Membership.invite(...)`(INVITED). 재구성은 Builder(영속 어댑터 전용).
- 권한 술어는 `OrgRole` enum 안에 산다: `canInviteMembers()`(OWNER|MANAGER) · `canManageMembers()`(OWNER 전용).
  서비스는 이 술어를 메서드 레퍼런스로만 참조 — 역할 비교를 서비스에 다시 쓰지 말 것.

## 핵심 불변식

- **OWNER 유일성은 없다(복수 OWNER 허용). 강제되는 것은 "활성 OWNER ≥ 1"**: 마지막 활성 OWNER 의
  강등·제거는 `LastOwnerException` → **422**(다른 409들과 구분). 카운트는 ACTIVE OWNER 만 — SUSPENDED OWNER 는 세지 않는다.
- **활성 슬롯 유일성의 최종 방어선은 DB 부분 UNIQUE**:
  `uq_membership_active ON memberships(organization_id, user_id) WHERE status IN ('INVITED','ACTIVE')`.
  `MembershipStatus.occupiesActiveSlot()`(INVITED|ACTIVE)과 이 인덱스의 상태 집합은 **같은 사실의 두 표현** —
  한쪽만 바꾸면 활성 유일성이 조용히 깨진다(`MembershipPersistenceAdapter.ACTIVE_SLOT` 도 동일 집합).
  SUSPENDED/REMOVED 는 슬롯을 비워 재초대 가능(설계 의도).
- **초대/수락은 멱등이 아니다(계약)**: 재초대·재수락은 409. 멱등화로 "고치지" 말 것 — 멱등 계층은
  발행측 Outbox `event_id` UNIQUE 와 소비측 `processed_events` 에만 있다.
- 조직 생성 = 조직 저장 + 생성자 OWNER 멤버십 + Outbox 발행이 **한 @Transactional** — 발행을 트랜잭션 밖으로 빼지 말 것.
- 동일 역할로의 changeRole 은 **무발행**(실제 변경 시에만 이벤트).

## 이벤트 4토픽 — card-service 프로젝션과의 계약 (드리프트 3종 주의)

| 토픽 | 발행 시점 | 핵심 |
| --- | --- | --- |
| `lemuel.organization.created` | 조직 생성 | `ownerUserId` 포함 — card 가 이걸로 OWNER 를 합성 등록 |
| `lemuel.organization.member_joined` | **초대 수락 시에만** | INVITED 시점엔 이벤트 없음 |
| `lemuel.organization.member_role_changed` | 실제 역할 변경 시만 | `previousRole` 필수 |
| `lemuel.organization.member_removed` | 제거 | 소비측이 프로젝션 비활성 + 이탈자 카드 SUSPENDED |

- **orderingKey(=Kafka key=aggregateId)는 4토픽 모두 `organizationId` 고정** — membershipId 로 바꾸면
  같은 조직의 joined/role_changed/removed 순서가 파티션 간에 흩어진다.
- **드리프트 함정**: ① 조직 생성 시 OWNER 용 member_joined 를 "친절하게" 추가 발행 → card 이중 등록
  (created.ownerUserId 가 이미 그 역할) ② member_removed 누락·지연 → **퇴사자 카드가 유효한 채 잔존**
  ③ previousRole 삭제·무변경 발행 → 소비측 권한 프로젝션 흔들림. 페이로드 변경은 계약 스키마
  (`contracts/events/lemuel.organization.*`) + 양방향 테스트와 함께(ADR 0024, `event-contract-change` 스킬).
- **eventType 문자열 = 토픽 파생**(`resolveTopic()`): 리네이밍은 topic-catalog.json·card application.yml·
  계약 스키마 파일명까지 4곳 동기 — 함부로 바꾸지 말 것.
- **컨슈머 추가 금지** — ArchUnit 이 타 서비스 코드 의존을 양방향 차단. 조직 SUSPENDED 를 소비측에
  알릴 이벤트는 현재 **없다** — suspend/activate 를 REST 로 노출하려면 새 토픽 계약 신설이 먼저다
  (도메인 메서드만 있고 유스케이스·배선 없음, `InboundPortReachabilityTest` 통과 필요).

## 권한 (IDOR)

- 표면은 `/api/organizations` 하나, 공개·admin 경로 0 — 전 엔드포인트 JWT(`anyRequest().authenticated()`).
- 주체는 `callerUserId(Authentication)` — `AuthPrincipal.userId()` 에서만 파생(요청에 주체 userId 자리가 없다).
- `orgId` 는 PathVariable 이되 신뢰하지 않는다 — `OrgAuthorizer.requireActiveMember(orgId, jwtUserId)` 가
  소속 재확인(403). 역할 판정은 항상 **ACTIVE 멤버십** 기준(SUSPENDED 멤버 권한 0).
- 초대 수락은 `findSlotOccupant(orgId, actingUserId)` 로 본인만 — 타인 초대 수락 불가.
- 예외→HTTP 정본은 `OrganizationExceptionHandler`: 404/403/409 + `LastOwnerException` 만 422.

## 배치

- `PartitionMaintenanceRunner`(매월 1일 02:30 KST) — 감사로그 파티션 선생성, prune 없음(장기 보존), 실패는
  warn 후 삼킴(fail-open). **ShedLock 없음이 의도** — 단일 인스턴스 위성. replica 확장 시 `@SchedulerLock` 도입 필요.
- 스키마 함정: 물리 DB 는 `lemuel_organization` 이지만 hibernate/flyway 스키마는 **`opslab`**(shared-common
  Outbox claim 네이티브 쿼리가 `opslab.outbox_events` 하드코딩) — 스키마를 바꾸면 Outbox 폴러가 조용히 죽는다.

## 안티패턴 (발견 시 지적)

- 마지막 활성 OWNER 강등·제거 허용 / OWNER 카운트에 SUSPENDED 포함.
- `occupiesActiveSlot()`·부분 UNIQUE 인덱스·`ACTIVE_SLOT` 상수 중 한쪽만 수정.
- 초대·수락 멱등화, 조직 생성 시 OWNER member_joined 추가 발행, 무변경 role_changed 발행.
- orderingKey 를 membershipId 로 변경 / eventType 문자열 리네이밍(=토픽 변경) 단독 수행.
- `@KafkaListener` 추가(발행 전용 경계 파괴) / Outbox 를 트랜잭션 밖으로.
- generic `IllegalState/IllegalArgumentException` — 타입 예외 6종이 정본이고 새 예외는 핸들러 매핑 동반.
- "소비처 없음/미배선" 서술 — card-service 4토픽 소비가 사실(문서 사실 게이트 FAIL).
