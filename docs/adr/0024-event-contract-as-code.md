# ADR 0024 — 이벤트 계약-as-code (JSON Schema 양방향 계약 테스트)

- 상태: Accepted (구현 완료)
- 일자: 2026-07-07

> **Update (이후 확장)**: 본문은 착수 시점의 cross-service 토픽 **8개**를 기준으로 기술한다. 이후
> investment/account 서비스 추가로 `lemuel.investment.executed`·`lemuel.loan.corporate_loan_disbursed` 2개,
> organization 서비스 추가로 `lemuel.organization.created`·`lemuel.organization.member_joined` 2개,
> 통신면 감사(2026-07-17) 후속으로 `lemuel.loan.disbursement_requested`(loan→account GL 분개)·
> `lemuel.company.reputation_changed`(company→loan 신용 프로젝션) 2개가 같은 패턴으로 편입되었다.
> 이후로도 organization 멤버십 2개(`member_role_changed`·`member_removed`)·card 8개·seller_recovery·
> seller.tier_changed·settlement holdback/withholding 계열, point 6·giftcard 4·insurance 9 등이 계속
> 편입되어, **현재 스키마·정본 샘플은 총 56개**다 (`../../shared-common/src/testFixtures/resources/contracts/events`
> — 실측: `ls .../contracts/events/*.schema.json | wc -l`. `git ls-files` 로 세면 아직 커밋되지 않은 신규
> 스키마가 빠져 커밋 직후에 처음 어긋난다). 아래 본문의 "cross-service 토픽 8개 전체"는
> **착수 시점 기준**이며 현재 커버리지가 아니다.
> 컨슈머 계약 테스트도 settlement·loan 에 더해 account(`AccountConsumerParsingTest` — 정본 샘플 기반)·
> company·card(`EventContractConsumerTest`)로 확장되었다. 결정·구조는 동일하며 커버리지만 넓어졌다.

> ADR 0022(Schema Registry)가 Proposed 로 남긴 "JSON + JSON Schema 검증(경량)" 대안을
> **선행 단계로 실행**한 결정. SR 마이그레이션(0022 단계 0~5)과 배타적이지 않으며,
> 여기서 명문화한 스키마가 그대로 0022 단계 1 의 Avro IDL 번역 원본이 된다.

## 컨텍스트

이벤트 페이로드가 서비스 간 계약이지만(ADR 0020 Phase 1 enrich), 계약을 강제하는 장치가
없었다 — 프로듀서는 `Map` 조립, 컨슈머는 `readTree` 파싱으로 계약이 **JSON 관례**로만 존재.
프로듀서가 필드명·타입을 바꾸거나 삭제해도 배포가 통과하고, 런타임에 컨슈머가 깨진다
(파싱 실패 → DLT, 또는 더 나쁘게 무성 null → 금액 오류). ADR 0022 는 Redpanda SR + Avro 를
설계했으나 직렬화 계층 전환·dual-format 마이그레이션이 필요해 착수 문턱이 높았다.

## 결정

**와이어 포맷은 현행 JSON 그대로 두고, 계약을 코드로 명문화해 빌드 시점에 강제한다.**

### 1. 계약 단일 출처: shared-common testFixtures

```
shared-common/src/testFixtures/
├── java/.../common/events/contract/EventContractValidator.java   # 검증기 (networknt json-schema-validator, draft 2020-12)
└── resources/contracts/events/
    ├── lemuel.payment.captured.schema.json        # order → settlement (정산 생성 + 프로젝션)
    ├── lemuel.payment.refunded.schema.json        # order → settlement (역정산 — anyOf: 금액 필드 최소 1개)
    ├── lemuel.settlement.created.schema.json      # settlement → loan (담보 뷰)
    ├── lemuel.settlement.confirmed.schema.json    # settlement → loan (상환 saga 진입)
    ├── lemuel.loan.repayment_applied.schema.json  # loan → settlement (순지급액 차감)
    ├── lemuel.order.created.schema.json           # order → settlement (settlement_order_view 프로젝션)
    ├── lemuel.user.registered.schema.json         # order → settlement (settlement_user_view 프로젝션)
    ├── lemuel.product.changed.schema.json         # order → settlement (settlement_product_view 프로젝션)
    └── samples/<topic>.sample.json                # 정본 샘플 — 컨슈머 계약 테스트 입력
```

- 소비 좌표: `testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))`
  — **프로덕션 클래스패스에 아무것도 추가하지 않는다** (계약은 테스트 시점 장치).
- 커버리지: money-critical 5개 + 프로젝션 3개 = **cross-service 토픽 8개 전체**.
  잔여 후보: `user.membership_changed`(cross-service 소비자 생기면), `lemuel.ops.*.failed`
  (best-effort 시그널 — 계약 강제 대상에서 의도적 제외).

### 2. 양방향 계약 테스트 (컨슈머 주도 계약 테스트의 경량 구현)

| 방향 | 위치 | 검증 내용 |
|---|---|---|
| **프로듀서** | `PaymentEventContractTest`·`OrderEventContractTest`·`UserEventContractTest`·`ProductEventContractTest`(order) · `SettlementEventContractTest`(settlement) · `LoanEventContractTest`(loan) | 발행 어댑터가 **실제로 조립한** outbox 페이로드가 스키마 통과 — required 필드 삭제·이름변경·타입변경 시 빌드 실패 |
| **컨슈머** | `EventContractConsumerTest`(settlement · loan) | **정본 샘플**을 실제 컨슈머 파싱 코드에 통과 → UseCase/프로젝션 뷰에 계약 값 그대로 도달하는지 검증 |
| **픽스처 자체** | `EventContractFixtureTest`(shared-common) | 모든 샘플이 자기 스키마 통과 + 위반(필수 삭제·타입 드리프트) 검출 확인 |

양측이 같은 스키마·샘플을 참조하므로, 한쪽이 계약을 바꾸면 CI(`gradlew build`)에서 깨진다.

### 3. 호환성 규칙 (스키마에 인코딩)

- `additionalProperties: true` — optional 필드 **추가는 언제나 허용**(전방 호환, enrich 패턴 유지)
- `required` — 컨슈머 정합성이 의존하는 필드만 (금액·식별자)
- 현행 직렬화의 **비대칭을 있는 그대로 명문화**: payment 계열 금액=문자열(`toPlainString`),
  settlement/loan 계열 금액=JSON number. 통일하려면 양측 동시 배포가 필요하므로 계약에 고정해
  드리프트만 차단한다(통일은 0022 Avro 전환 시 자연 해소).
- `lemuel.payment.refunded` 는 `anyOf` 로 "금액 필드(건별 refundAmount 또는 누적 refundedAmount)
  최소 1개"를 강제 — 금액 없는 환불 이벤트는 계약 위반.

## 결과

### 좋아지는 점
- 계약 드리프트가 런타임(DLT/무성 null)이 아닌 **빌드 시점에 차단** — 0022 의 핵심 목표를
  인프라 추가 0, 와이어 포맷 변경 0 으로 부분 달성
- 스키마 + 정본 샘플이 곧 문서 — 이벤트 계약의 코드 밖 단일 출처
- 0022 실행 시 스키마가 Avro IDL 초안으로 재사용됨 (단계 1 비용 절감)

### 한계 (0022 대비)
- **배포 순서 독립성 미보장**: 검증은 각 서비스의 빌드 안에서 일어나므로, 프로듀서만 배포하고
  컨슈머 빌드를 안 돌리면 감지 시점이 늦다 → CI 가 전 모듈 빌드를 돌리는 현 구조에서만 유효
- 호환성 자동판정(FULL_TRANSITIVE 같은 이력 대상 규칙) 없음 — required 축소는 사람이 판단
- 런타임 강제 아님 — 스키마를 안 거친 수동 발행(rpk 등)은 여전히 통과

## 참조

- 0020 — order↔settlement DB 물리 분리 (Phase 1 enrich = 계약의 기원)
- [0022 — 이벤트 Schema Registry](0022-event-schema-registry.md) (본 ADR 은 그 경량 선행 단계)
- [0017 — Kafka 컨슈머 DLT + Replay](0017-kafka-consumer-dlt-and-replay.md) (계약 위반의 런타임 증상)
