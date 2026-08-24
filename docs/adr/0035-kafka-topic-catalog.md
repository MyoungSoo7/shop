# ADR 0035 — Kafka 토픽 카탈로그 (파티션 수를 코드 안으로)

- 상태: Accepted (구현 완료)
- 일자: 2026-08-13

## 컨텍스트

이 저장소는 **키 기반 순서 보장**을 쓴다. `KafkaOutboxPublisher` 가 outbox 의 `aggregateId` 를 메시지
키로 실어 발행하므로(`KafkaOutboxPublisher#buildRecord`), 같은 결제/정산의 이벤트는 같은 파티션에
쌓이고 그 안에서 시간 순서가 보장된다. 정산·원장처럼 순서가 회계 결과를 바꾸는 도메인에서 이 보장은
설계의 전제다.

그런데 그 보장의 제수(除數)인 **파티션 수가 코드에 없었다.**

- `NewTopic` 선언은 `shared-common` 의 `KafkaConfig` 하나뿐이었고, 거기 선언된 토픽은
  `lemuel.payment.captured`·`lemuel.payment.refunded` 와 두 DLT — **4개**였다.
- 반면 서비스들이 실제로 발행·구독하는 토픽은 **50개가 넘는다**(settlement 11 · card 8 · insurance 8 ·
  loan 7 · order 6 · deposit 5 · organization 4 · company/investment 각 1).
- 나머지 46여 개는 브로커 자동생성에 맡겨졌다. Redpanda 의 `default_topic_partitions` 기본값은 **1** 이다.

여기서 두 가지가 따라온다.

**첫째, 조정 손잡이가 없다.** 7개 서비스가 `concurrency: 3` 을 켜 두었지만 컨슈머 병렬 소비의 상한은
파티션 수다. 파티션이 1이면 나머지 스레드는 논다. `../../monitoring/grafana/dashboards/kafka-lag.json` 은
"concurrency/partitions 늘리면 0으로 수렴해야 정상"이라 안내하는데, 그 `partitions` 를 조정할 선언이
대부분 토픽에 없었다.

**둘째, 그리고 더 중요하게, 파티션 확대는 되돌릴 수 없다.** 파티션 수 N 이 바뀌면 `hash(key) % N` 이
바뀌어 같은 애그리거트의 이벤트가 다른 파티션으로 흩어진다 — 이미 쌓인 메시지에 대해서까지 순서
보장이 **소급 붕괴**한다. 인프런 「핵심만 빠르게 끝내는 실전 카프카」(bradkim)가 "키값을 사용하는
토픽은 파티션 개수 변경이 구조적으로 어려우니 초기에 여유 있게 잡으라"고 말하는 지점이 정확히 여기다.
되돌릴 수 없는 결정이라면 코드 리뷰를 거쳐야 하고, 그러려면 값이 코드 안에 있어야 한다.

이 위험은 이미 한 번 현실화됐다. `notification-service/.../KafkaErrorHandlingConfig.kt` 에 실측 기록이
남아 있다 — `lemuel.payment.captured` 가 6 파티션인데 그 DLT 는 3 이어서, 파티션 3~5 의 레코드는
존재하지 않는 파티션으로 라우팅되어 **격리 발행 자체가 실패**했다. 원본은 늘었는데 자동생성된 DLT 는
따라오지 않았기 때문이다.

## 결정

**토픽의 전송 속성을 코드 안의 단일 출처로 옮기고, 없는 토픽만 만든다.**

### 1. 카탈로그: `../../shared-common/src/main/resources/kafka/topic-catalog.json`

페이로드 계약은 ADR 0024(`testFixtures/contracts/events/`)가 정본이다. 이 카탈로그는 그 계약이 다루지
않는 **전송** 속성을 맡는다. 계약과 달리 전송 속성은 프로덕션 기동 시점에 필요하므로 `src/main` 에 둔다.

| 필드 | 의미 |
|---|---|
| `name` | 토픽명 |
| `owner` | 이 토픽을 **발행**하는 Gradle 모듈. 토픽을 만드는 주체는 프로듀서 하나뿐이다 |
| `orderingKey` | 메시지 키의 도메인 의미 — 무엇의 시간 순서를 지키는가 |
| `partitions` | 파티션 수. **변경은 키 재해시 = 순서 보장 소급 붕괴** |
| `retentionDays` | 보존기간 |

**DLT 는 등록하지 않는다.** `TopicCatalog.Topic#deadLetterSpec()` 이 원본에서 파생하며 파티션 수가 항상
원본과 같다. 파생값이면 둘이 어긋날 수 없다 — 위 실측 사고의 구조적 재발 방지다.

### 1-1. 속성별 취급의 비대칭 (2026-08-14 개정)

처음에는 세 속성을 모두 "만들 때만 적용"으로 통일했다. 그건 **파티션의 성질에 맞춘 규칙을 나머지에까지
적용한 실수**였다. 실측에서 드러났듯 기존 토픽의 보존기간은 카탈로그가 뭐라 적혀 있든 클러스터
기본값을 물려받고 있었다 — 선언은 있는데 효력이 없는 **쓰기 전용 문서**였다.

| 속성 | 변경하면 | 되돌릴 수 있나 | 프로비저너의 처리 |
|---|---|---|---|
| **partitions** | 키 재해시 → 순서 보장 **소급 붕괴** | ❌ | 만들 때만. 불일치는 드리프트 보고 |
| **retentionDays** | 로그 삭제 시점만 변함. 키·순서 무관 | ✅ | **기동마다 토픽에 고정** |
| **replicas** | 파티션 재배치 필요, 브로커 수 종속 | △ | 보고만 |

보존기간은 **값이 같아도 고정한다**. `SOURCE=DEFAULT_CONFIG` 는 "지금 우연히 같은 값"일 뿐이고,
누가 `log_retention_ms` 를 바꾸면 전 토픽이 조용히 따라 바뀐다. 그 상속을 끊어야 카탈로그가 보장이 된다.

`replicas` 는 `KafkaClientTopicAdmin` 의 `private static final short REPLICAS = 1` 상수였다 — 파티션을
카탈로그로 끌어오면서 정작 옆의 같은 문제를 남겨뒀던 것이라, 토픽별 선언으로 옮기고 게이트가 강제한다.

### 2. 프로비저닝: 파티션은 만들기만, 보존기간은 맞춘다

Spring 의 `KafkaAdmin` 은 "`NewTopic` 이 선언한 파티션이 기존 토픽보다 많으면 파티션을 늘린다"(Spring
Kafka 레퍼런스 *Configuring Topics*). 편의 기능이지만 이 저장소에서는 사고다 — 토픽 정의를 코드로
옮기는 작업이 바로 그 재해시를 유발하면 본말전도다. 그래서 `NewTopic` 빈을 쓰지 않는다.

- `TopicAdmin` 포트에는 **생성만** 있다. 증설·삭제 메서드가 없으므로 실수로 부를 수도 없다.
- `TopicProvisioner` 는 없는 토픽만 만들고, 파티션 수가 카탈로그와 다르면 **`Drift` 로 보고**한다.
- `TopicProvisioningInitializer` 가 기동 시(리스너 컨테이너보다 먼저) 자기 모듈 소유 토픽만 프로비저닝하고,
  드리프트를 `kafka.topic.partition.drift` 게이지로 노출한다. 브로커에 닿지 못해도 **기동은 막지 않는다**.

조치는 사람이 판단한다 — 브로커를 카탈로그에 맞출지(`rpk topic add-partitions`), 카탈로그를 현실에
맞출지는 도메인 판단이다.

### 3. 소유권: `app.kafka.topic.owner`

발행 서비스 9곳(order·settlement·loan·company·investment·organization·card·insurance·deposit)에만 선언한다.
컨슈머 전용 서비스는 비워 두고 프로비저닝을 건너뛴다. 컨슈머가 토픽을 만들면 파티션 수 결정 주체가
둘이 되어 드리프트가 재발한다.

같이 제거한 것: `app.kafka.topic.partitions`. 4개 토픽에만 닿던 죽은 손잡이였다(9개 yml).

### 4. 게이트: `../../scripts/harness/test/kafka-topic-gate.test.mjs`

`harness-guard.yml` 의 `node --test scripts/harness/test/*.test.mjs` 로 CI 에서 자동 수집된다.

- **참조되는 모든 토픽이 카탈로그에 있다** — 없으면 브로커 기본값으로 자동생성된다(= 파티션이 코드 밖)
- 토픽명 중복 없음 / `.DLT` 직접 등록 금지 / owner 는 실재 모듈 / 토픽당 소유자 1
- `owner`·`orderingKey`·`partitions`·`retentionDays` 전부 선언
- 빈 스캔 방지 어서션 — 추출기가 깨져 "0건이라 통과"하는 경로를 막는다

### 5. 발행부 대조 게이트: `kafka-publisher-gate.test.mjs` (2026-08-14 추가)

위 게이트는 `application.yml` 만 본다. 그래서 **구독 설정이 없는 발행 전용 토픽**은 카탈로그에서 빠져도
아무도 모른다. 실제로 두 번 났다 — `insurance.general_payout_{requested,paid}`(발행 코드는 있는데
카탈로그에 없음), `card.statement.paid`(계약 스키마 파일명을 옮겨 적어 실재하지 않는 이름이 등재됨).

그래서 정본을 yml 이 아니라 **발행 코드**에서 가져온다. `OutboxEvent.pending(aggregateType,
aggregateId, eventType, …)` 호출부를 파싱해 `KafkaOutboxPublisher` 와 **같은 규칙**으로 토픽명을
계산하고(`resolveTopic` + `camelToSnake` 를 게이트가 복제, 단위 테스트로 동치 고정) 카탈로그와 대조한다.

- 발행되는 모든 토픽이 카탈로그에 있다
- 카탈로그 `owner` == 그 발행부가 속한 Gradle 모듈
- `orderingKey` == 발행부의 `aggregateId` 에서 뽑은 키 이름

**실측 커버리지(2026-08-14, 결정 당시)**: 호출부 40건 해석 / 6건 미해석, 고유 토픽 39개(당시 카탈로그
52개 중 39개를 발행부로 교차검증), `orderingKey` 실제 대조 28건 / 판정 보류 12건.
이 수치는 결정 시점의 감사 기록이다 — 현재 등재 수는 카탈로그 파일이 정본이다(2026-08-23 기준 63건).

**판정을 보류하는 경우**를 명시해 둔다. `String.valueOf(account.getId())` 처럼 게터가 일반형(`getId`)이면
수신자 변수명(`account`)이 카탈로그 용어와 다를 수 있어 대조 근거가 못 된다 — 오탐보다 범위 축소를
택했다. `eventType` 이 변수인 호출부(deposit 처럼 래퍼 메서드 경유)는 토픽을 계산할 수 없어 미해석으로
세고, **그 수에 상한(12)을 둬** 래퍼 발행이 늘면 게이트가 조용히 눈감는 일을 막는다.

## 결과

### 좋아지는 점

- 파티션 수가 **리뷰 가능한 값**이 됐다. 되돌릴 수 없는 변경이 조용히 일어나지 않는다.
- DLT 파티션 불일치가 **구조적으로** 불가능해졌다(파생값).
- `orderingKey` 선언 강제 = 토픽을 추가할 때 "무엇의 순서를 지키는가"를 항상 답하게 된다.
- 브로커 현실과 카탈로그의 어긋남이 게이지로 드러난다 — 기존에는 관측 수단 자체가 없었다.

### 한계 (정직하게)

- ~~브로커 실측 미반영~~ → **해소 (2026-08-14)**. 아래 "실측 결과" 참조.
- ~~`orderingKey` 값은 미검증~~ → **해소 (2026-08-14)**. 1차로 publisher 를 손으로 대조했고(아래
  "orderingKey publisher 대조"), 이후 `kafka-publisher-gate` 로 기계화했다(§5). 다만 게터가 일반형인
  12건은 판정 보류라 여전히 사람이 봐야 한다.
- ~~발행 전용 토픽은 게이트 사각지대~~ → **해소 (2026-08-14)**, §5. 단 `eventType` 이 변수인 호출부
  6건(deposit 등 래퍼 경유)은 토픽을 계산할 수 없어 여전히 사각지대다 — 수를 상한으로 잠가뒀다.
- 런타임 강제 아님 — `rpk` 로 사람이 만든 토픽은 카탈로그를 거치지 않는다(드리프트로만 드러난다).
- 파티션 **변경** 자체를 막지는 않는다. 카탈로그 값이 바뀌면 diff 에 드러나 리뷰에 걸리지만, 전용
  가드 규칙(변경 시 마이그레이션 근거 요구)은 넣지 않았다.

## 실측 결과 (2026-08-14)

로컬 브로커(`lemuel-redpanda`, 볼륨 `settlement_redpanda-data`)에서 `rpk topic list` 로 33개 `lemuel.*`
토픽을 실측했다. **가설이 그대로 확인됐다 — 자동생성 토픽은 전부 파티션 1**이었다.

특히 `lemuel.payment.captured` 는 **6 파티션인데 그 DLT 는 3** 이었다. notification-service 주석에
기록된 사고가 브로커에 그대로 살아 있었다는 뜻이다(파티션 3~5 의 레코드는 격리 발행 자체가 실패한다).

조정은 **메시지가 있는지**를 기준으로 갈랐다(`HIGH-WATERMARK` 합계):

| 구분 | 조치 | 대상 |
|---|---|---|
| 비어 있음 | 브로커를 3으로 증설 (재해시 없음) | settlement.{adjusted,canceled,holdback_consumed,withholding_accrued}, seller_recovery.{opened,offset}, pgreconciliation.discrepancy_approved, loan.corporate_loan_disbursed |
| 비어 있음(DLT) | 3 → 6 으로 증설 — **기록된 사고 해소** | payment.captured.DLT |
| 데이터 보유 | 카탈로그를 실측값 1 로 하향 | order.created(1552), settlement.created(1317), settlement.holdback_released(1310), payout.completed(200), product.changed(64), user.registered(43), 외 5종 |
| 이미 일치 | 변경 없음 | payment.refunded(3), payment.refunded.DLT(3) |
| 실측 반영 | 카탈로그 3 → 6 (축소 불가) | payment.captured |

결과: **카탈로그 ↔ 브로커 불일치 0건**(브로커에 존재하는 23개 항목 기준). 나머지는 아직 미생성이며
소유 서비스 기동 시 프로비저너가 만든다.

`1` 로 적힌 토픽을 나중에 올리려면 드레인 후 계획된 마이그레이션이 필요하다 — 카탈로그만 고치면
프로비저너는 증설하지 않고 드리프트 게이지로만 뜬다(설계대로다).

**의도적 제외** (카탈로그 밖 브로커 토픽 9개): `lemuel.ops.*` 5종은 `KafkaOpsSignalPublisher` 가
모든 서비스에서 발행해 `owner` 가 하나로 정해지지 않고 best-effort 라 순서 보장 대상이 아니다.
`lemuel.payment.{authorized,confirmed,created}` 는 어느 서비스도 참조하지 않는 레거시,
`lemuel.user.membership_changed` 는 ADR 0024 가 남긴 잔여 후보다.

## 보존기간·복제본 실측 (2026-08-14, 2차)

파티션을 맞춘 뒤 남은 두 속성을 재보니, 카탈로그의 선언이 브로커에 **전혀 반영돼 있지 않았다.**

```
lemuel.payment.captured   retention.ms=604800000(7일)  SOURCE=DEFAULT_CONFIG
lemuel.order.created      retention.ms=604800000(7일)  SOURCE=DEFAULT_CONFIG
클러스터 log_retention_ms 604800000
```

`SOURCE=DEFAULT_CONFIG` 가 핵심이다. 이 7일은 토픽 설정조차 아니고 **클러스터 기본값**이었다. 카탈로그의
`retentionDays` 는 신규 생성 시에만 실렸고, 프로비저너는 보존기간을 비교조차 하지 않았다.

증상이 DLT 에서 그대로 드러났다 — ADR 은 "DLT 는 원본보다 길게 보존해 운영자가 사후 분석할 시간을
확보한다"고 적어놨는데:

| DLT | 보존 | 출처 |
|---|---|---|
| payment.captured.DLT · payment.refunded.DLT | 30일 | DYNAMIC_TOPIC_CONFIG (구 `NewTopic` 빈이 설정) |
| user.membership_changed.DLT | **7일** | DEFAULT_CONFIG (자동생성) |

**조치**: `TopicAdmin` 에 `alterRetention` 을 추가해 프로비저너가 기동마다 고정하도록 했고(값이 같아도
상속 상태면 고정), 기존 토픽 23개는 `rpk topic alter-config` 로 1회 정렬했다.
**검증: 23/23 이 `DYNAMIC_TOPIC_CONFIG` 로 고정, 카탈로그 대비 불일치 0건.**

복제본은 전 토픽 RF=1 이었다(브로커 1대라 그 이상 불가). 값 자체는 정상이지만 **선언 위치가 문제였다** —
코드 상수라 토픽별로 다르게 줄 수도, 리뷰할 수도 없었다. 카탈로그 `replicas` 필드로 옮기고 게이트가
선언을 강제한다. 프로덕션 전환 시 이 값이 리뷰 대상이 된다.

## orderingKey publisher 대조 (2026-08-14)

`KafkaOutboxPublisher#buildRecord` 가 싣는 메시지 키는 `OutboxEvent.pending(aggregateType, **aggregateId**, …)`
의 두 번째 인자다. 전 서비스의 호출부 49개를 뽑아 카탈로그와 대조했다. 계약 스키마의 첫 식별자 필드에서
도출한 초기값은 **12건이 실제 키와 달랐다.**

| 토픽 | 카탈로그(초기) | 실제 `aggregateId` |
|---|---|---|
| card.issued · card.status_changed | `cardId` | `account.getId()` = **cardAccountId** |
| card.authorized | `authorizationId` | `hold.getCardAccountId()` = **cardAccountId** |
| card.captured | `captureId` | `capture.getCardAccountId()` = **cardAccountId** |
| card.statement_paid | `statementId` | `statement.getCardAccountId()` = **cardAccountId** |
| insurance.policy_issued · policy_status_changed · commission_{confirmed,paid,clawback_triggered} | `policyId` / `commissionId` | `policy.getPolicyNumber()` = **policyNumber** |
| insurance.commission_monthly_closed | `commissionId` | `closing.getFcId()` = **fcId** (설계사 단위) |
| insurance.banca_rule_violated | `policyId` | `violation.bankCode()` = **bankCode** (은행 단위) |
| settlement.holdback_consumed | `adjustmentId` | `sourceAdjustmentId` |

card 8종이 전부 `cardAccountId` 로 묶이는 것은 의도된 설계다(어댑터 주석 `// 파티션 키 = cardAccountId`) —
카드 계정 단위로 승인·매입·명세서의 순서를 지킨다. 나머지 39개 토픽은 초기값이 실제와 일치했다.

같이 드러난 것:

- **누락 토픽 2개**: `lemuel.insurance.general_payout_{requested,paid}` 은 발행 코드가 있는데 카탈로그에
  없었다. `app.kafka.topic.*` 로 참조되지 않아 게이트가 잡지 못했다(게이트의 사각지대 — 발행 전용 토픽).
- **토픽명 오류 1개**: 카탈로그에 `lemuel.card.statement_paid` 로 적혀 있었으나, `resolveTopic` 규칙상
  실제 발행명은 `lemuel.card.statement_paid` 다(`Card` + `CardStatementPaid` → `statement_paid`).
  초기값을 계약 스키마 파일명에서 옮긴 탓이며, **그 스키마 파일명 자체가 실제 토픽과 다르다**
  (`contracts/events/lemuel.card.statement_paid.schema.json` — ADR 0024 영역, 미수정).
- **프로듀서 없는 토픽 1개**: `lemuel.insurance.coverage_bound` 는 insurance yml 이 선언하지만 발행 코드가
  없다(전 저장소 검색 0건). 설정에만 존재하는 유령 토픽이다 — 미조치.

## 참조

- [0024 — 이벤트 계약-as-code](0024-event-contract-as-code.md) (페이로드 계약 정본. 본 ADR 은 전송 속성 담당)
- 0020 — order↔settlement DB 물리 분리 (키 기반 프로젝션의 기원)
- [0017 — Kafka 컨슈머 DLT + Replay](0017-kafka-consumer-dlt-and-replay.md) (DLT 파티션 불일치의 무대)
- 인프런 「핵심만 빠르게 끝내는 실전 카프카」(bradkim) — 파티션 개수 결정 요인, 키-해시 매핑
