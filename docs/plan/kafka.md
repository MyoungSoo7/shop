# Kafka 전송 계약·운영 정본

이 저장소의 서비스 간 연계는 **Kafka 이벤트로만** 이루어진다(코드·DB 직접 의존 0). 이 문서는 그
이벤트가 **어떤 전송 속성 위에서 움직이는가**와 **그것을 무엇이 지키는가**를 한 곳에 모은다.

ㄹㅁ경계를 먼저 긋는다 — 겹치는 문서가 여럿이다.전

| 관심사 | 정본 |
| --- | --- |
| **전송** 속성(파티션·보존·복제본·순서키·소유자)과 운영 절차 | **이 문서** + `../../shared-common/src/main/resources/kafka/topic-catalog.json` |
| 그 설계 결정과 근거 | [ADR 0035](../adr/0035-kafka-topic-catalog.md) |
| **페이로드** 계약(JSON Schema·정본 샘플) | [ADR 0024](../adr/0024-event-contract-as-code.md) |
| 토픽 목록·프로듀서/컨슈머 매핑 | [`../../SPEC.md`](../../SPEC.md) §5 |
| 코드 작성 규칙(멱등 3단·신규 컨슈머 체크리스트) | `idempotency-and-events` 스킬 |
| DLT 격리·리플레이 | [ADR 0017](../adr/0017-kafka-consumer-dlt-and-replay.md) |

---

## 1. 한 장으로 보는 흐름

```
비즈니스 tx ──┬─ 도메인 테이블 INSERT/UPDATE
              └─ outbox_events INSERT (같은 tx, event_id UUID)
                        │
                 멀티워커 폴러 (FOR UPDATE SKIP LOCKED, 2s)
                        │
              KafkaOutboxPublisher
                 topic = lemuel.<aggregateType>.<event_snake>
                 key   = aggregateId          ← 순서 보장의 근거
                 header= event_id·event_type·occurred_at·traceparent
                        │
                    [ 토픽 ]  파티션 = hash(key) % N
                        │
              @KafkaListener (group-id = lemuel-<모듈>)
                 ① processed_events(group, event_id) 멱등 체크 — 첫 줄
                 ② 비즈니스 로직 (같은 tx)
                 ③ ack.acknowledge()  (manual_immediate)
                        │
                  실패 → 재시도 → <topic>.DLT 격리
```

**핵심 한 줄**: 메시지 키가 outbox `aggregateId` 이므로, **같은 애그리거트의 이벤트는 같은
파티션에 쌓이고 그 안에서 시간 순서가 보장된다.** 정산·원장처럼 순서가 회계 결과를 바꾸는
도메인에서 이 보장은 설계의 전제다.

---

## 2. 토픽 카탈로그

정본: `../../shared-common/src/main/resources/kafka/topic-catalog.json` (**63개 토픽** — 검증:
`node -e "console.log(require('./shared-common/src/main/resources/kafka/topic-catalog.json').topics.length)"`.
계약 스키마 56개보다 넓다 — `lemuel.ops.*` 등 계약 없는 내부 토픽도 전송 속성은 카탈로그가 관리한다)

| 필드 | 의미 |
| --- | --- |
| `name` | 토픽명. `KafkaOutboxPublisher#resolveTopic` 이 계산하는 값과 **반드시 일치**해야 한다 |
| `owner` | 이 토픽을 **발행**하는 Gradle 모듈. 토픽을 만드는 주체는 프로듀서 하나뿐이다 |
| `orderingKey` | 메시지 키의 도메인 의미 = outbox `aggregateId` 가 담는 값 |
| `partitions` | 파티션 수. 컨슈머 병렬 소비의 상한이자 **키 해시의 제수** |
| `replicas` | 복제본 수. 브로커 수를 넘을 수 없다 |
| `retentionDays` | 보존기간 |

**DLT 는 등록하지 않는다.** `TopicCatalog.Topic#deadLetterSpec()` 이 원본에서 파생하며 파티션 수가
항상 원본과 같다(보존은 30일). 파생값이면 둘이 어긋날 수 없다 — 원본 6 / DLT 3 으로 갈려
격리 발행 자체가 실패했던 실측 사고의 구조적 재발 방지다.

### 토픽명 규칙

```
aggregateType="Card", eventType="CardStatementPaid"
  → 접두사 제거 "StatementPaid" → camelToSnake → lemuel.card.statement_paid
```

계약 스키마 파일명(`contracts/events/<topic>.schema.json`)도 **이 계산 결과와 같아야** 한다.
파일명을 손으로 적다 `lemuel.card.statement.paid`(실재하지 않는 이름)가 등재된 적이 있다.

---

## 3. 속성별 취급이 다르다 (되돌릴 수 있는가)

세 속성을 "만들 때만 적용"으로 통일한 것이 초기 설계의 실수였다. 성질이 다르다.

| 속성 | 바꾸면 | 되돌리기 | 프로비저너 |
| --- | --- | --- | --- |
| **partitions** | `hash(key) % N` 이 바뀌어 같은 애그리거트의 이벤트가 다른 파티션으로 흩어진다 — **이미 쌓인 메시지까지 순서 보장이 소급 붕괴** | ❌ | 만들 때만. 불일치는 드리프트 **보고만** |
| **retentionDays** | 로그 삭제 시점만 변한다. 키·순서와 무관 | ✅ | **기동마다 토픽에 고정** |
| **replicas** | 파티션 재배치가 필요하고 브로커 수에 종속 | △ | 보고만 |

보존기간은 **값이 같아도 고정한다.** `SOURCE=DEFAULT_CONFIG` 는 "지금 우연히 같은 값"일 뿐이라,
누가 클러스터 `log_retention_ms` 를 바꾸면 전 토픽이 조용히 따라 바뀐다.

> **왜 Spring 의 `NewTopic` 빈을 쓰지 않는가**: `KafkaAdmin` 은 "NewTopic 이 선언한 파티션이 기존
> 토픽보다 많으면 파티션을 늘린다"(Spring Kafka 레퍼런스 *Configuring Topics*). 편의 기능이지만
> 여기서는 사고다. `TopicAdmin` 포트에는 **증설·삭제 메서드가 아예 없다** — 부를 수 없으면 실수도 없다.

---

## 4. 컨슈머 규약

| 항목 | 값 | 이유 |
| --- | --- | --- |
| `group-id` | `lemuel-<모듈명>` | 같은 그룹을 두 서비스가 쓰면 파티션을 나눠 갖고 오프셋까지 공유해 **조용히 유실**된다 |
| `enable-auto-commit` | `false` | 처리 실패 시에도 오프셋이 넘어가면 유실이다 |
| `ack-mode` | `manual_immediate` | 멱등 체크 + 비즈니스 로직이 같은 tx 로 끝난 뒤에만 커밋 |
| `auto-offset-reset` | `earliest` (operation 만 `latest`) | 새 그룹이 과거분까지 처리해야 하는지의 문제 |
| `isolation.level` | `read_committed` | |
| `concurrency` | 3 | **유효 상한은 그 토픽의 파티션 수**다 |

### 그룹 ID 변경은 "전량 재생 스위치"다

`processed_events` PK 가 `(consumer_group, event_id)` 이므로 **그룹 ID 를 바꾸면 2층 멱등이 통째로
무효**가 되고, `earliest` 라 보존기간 안의 이벤트가 전량 재처리된다. 그때 남는 방어는 3층
(도메인 자연키 UNIQUE)뿐이다. 신규 컨슈머 6항목 체크리스트는 `idempotency-and-events` 스킬에 있다.

### 팬아웃 vs 분산

- 같은 토픽을 **여러 서비스가 각자** 처리 → 그룹 ID 가 서로 **달라야** 한다
  (`lemuel.settlement.confirmed` 를 account·deposit·investment·loan 이 각각 소비)
- 같은 서비스를 **N대로 늘려** 처리량을 올린다 → 그룹 ID 는 **같아야** 한다

---

## 5. 무엇이 이것을 지키는가 (게이트 4종)

CI `harness-guard.yml` 이 `node --test scripts/harness/test/*.test.mjs` 로 전부 수집한다.

| 게이트 | 보는 축 | 막는 것 |
| --- | --- | --- |
| `kafka-topic-gate` | **yml** (`app.kafka.topic.*`) | 참조되는데 카탈로그에 없는 토픽 · 중복 · `.DLT` 직등록 · 필드 미선언 · owner 실재성 |
| `kafka-publisher-gate` | **발행 코드** (`OutboxEvent.pending`) | 발행되는데 카탈로그에 없는 토픽 · owner 불일치 · `orderingKey` 불일치 |
| guard `KAFKA-GROUP-OWNER` | application.yml | 모듈 소유가 아닌 group-id |
| guard `KAFKA-DLQ` | 모듈 배선 | `@KafkaListener` 가 있는데 DLT 배선이 닿지 않음 |

**두 게이트의 축이 다른 이유**: yml 게이트는 구독 설정이 없는 **발행 전용 토픽**을 보지 못한다.
실제로 `lemuel.insurance.general_payout_{requested,paid}` 2건이 그렇게 빠져 있었다.

`kafka-publisher-gate` 는 `resolveTopic`·`camelToSnake` 를 복제하므로, **Java 쪽 규칙이 바뀌면
게이트의 단위 테스트가 먼저 깨지도록** 동치를 고정해 뒀다.

---

## 6. 운영 절차

### 실측

```bash
docker start lemuel-redpanda
docker exec lemuel-redpanda rpk cluster health
docker exec lemuel-redpanda rpk topic list                 # NAME PARTITIONS REPLICAS
docker exec lemuel-redpanda rpk topic describe <t> -p      # HIGH-WATERMARK 는 6번째 컬럼
docker exec lemuel-redpanda rpk topic describe <t> -c      # retention.ms 의 SOURCE 확인
```

> ⚠️ `describe -p` 의 4번째 컬럼은 `REPLICAS` 다. 이걸 워터마크로 오독하면 "토픽이 비어 있다"는
> 정반대 결론이 나온다. 파티션 증설처럼 **되돌릴 수 없는 조치**의 근거로 쓸 값은 두 번 확인한다.

### 파티션을 올려야 할 때

1. 해당 토픽에 **메시지가 있는지** 먼저 본다(HIGH-WATERMARK 합).
2. **비어 있으면** 지금이 유일하게 안전한 창이다 — 배치된 키가 없어 재해시 결과가 없다.
   `rpk topic add-partitions <t> --num <증분>` 후 카탈로그를 같은 값으로 맞춘다.
3. **데이터가 있으면** 증설은 순서 보장을 소급해서 깬다. 드레인(컨슈머가 따라잡고 발행 중단) 후
   계획된 마이그레이션으로 처리하거나, 새 토픽으로 옮긴다.
4. 카탈로그만 고치면 프로비저너는 증설하지 않고 `kafka.topic.partition.drift` 게이지로만 알린다.
   **이것이 설계대로다** — 되돌릴 수 없는 변경은 사람이 판단한다.

### 보존기간

프로비저너가 기동마다 카탈로그 값으로 고정한다. 브로커에 이미 있는 토픽을 일괄 정렬하려면
`rpk topic alter-config <t> --set retention.ms=<ms>`.

### DLT

- 인스펙션·리플레이: settlement 는 `GET /admin/dlq/inspect` · `POST /admin/dlq/replay`
- 그 외 서비스는 수작업 경로다 — 러너북 [`../plan/runbook/notification-dlt.md`](../plan/runbook/notification-dlt.md)
- 리플레이가 안전한 이유는 멱등 3단 방어다. 멱등이 없는 컨슈머에 리플레이하면 이중 계상이 된다.

### 관측

`kafka-lag` 대시보드(`../../monitoring/grafana/dashboards/kafka-lag.json`) — lag 이 안 줄면 파티션 수와
`concurrency` 를 함께 본다. `kafka.topic.partition.drift` 가 0 이 아니면 카탈로그와 브로커가 어긋난 상태다.

### 로컬 주의

- rpk 로 직접 produce 시 `-z none` 필수(이미지에 snappy 네이티브 없음).
- 컨테이너 정지는 `docker stop lemuel-redpanda`. **`docker compose down -v` 는 금지** —
  볼륨 `settlement_redpanda-data` 에 토픽 정의가 들어 있어 맞춰 둔 상태가 통째로 사라진다.

---

## 7. 실측 스냅샷 (2026-08-14)

로컬 브로커 기준이며, 카탈로그는 이 값에 맞춰져 있다(불일치 0건).

| 구분 | 값 |
| --- | --- |
| 카탈로그 토픽 | 52개 (파티션 1: 11 · 3: 40 · 6: 1) |
| 브로커 `lemuel.*` 토픽 | 33개 (나머지는 소유 서비스 기동 시 프로비저너가 생성) |
| 복제본 | 전 토픽 1 (브로커 1대) |
| 보존기간 | 카탈로그 대상 23개 전부 `DYNAMIC_TOPIC_CONFIG` 로 고정 |

**파티션 1인 11개**(데이터 보유분): `order.created`(1552건) · `settlement.created`(1317) ·
`settlement.holdback_released`(1310) · `payout.completed`(200) · `product.changed`(64) ·
`user.registered`(43) · `settlement.confirmed` · `investment.executed` ·
`company.reputation_changed` · `loan.disbursement_requested` · `loan.repayment_applied`.
올리려면 위 "파티션을 올려야 할 때" 3번 경로를 따른다.

**의도적 제외**: `lemuel.ops.*` 5종(모든 서비스가 발행해 owner 가 하나로 정해지지 않고 best-effort) ·
`lemuel.payment.{authorized,confirmed,created}`(레거시, 미참조) · `lemuel.user.membership_changed`
(cross-service 소비자가 생기면 편입).

---

## 8. 알려진 사각지대

| 사각지대 | 내용 | 완화 |
| --- | --- | --- |
| 래퍼 경유 발행 | `eventType` 이 변수인 호출부 6건(deposit 등)은 토픽 계산이 불가능해 publisher 게이트 밖 | 미해석 수 상한 12 — 늘면 CI 가 먼저 빨개진다 |
| `orderingKey` 판정 보류 | 게터가 일반형(`getId()`)인 12건은 카탈로그 용어와 대조할 근거가 없다 | 사람이 리뷰 |
| 런타임 미강제 | `rpk` 로 사람이 만든 토픽은 카탈로그를 거치지 않는다 | 드리프트 게이지로 사후 검출 |
| 파티션 값 변경 자체 | 카탈로그 diff 로 리뷰에 걸리지만 전용 가드는 없다 | 리뷰 |

---

## 참조

- [ADR 0035 — Kafka 토픽 카탈로그](../adr/0035-kafka-topic-catalog.md) (결정·실측 이력)
- [ADR 0024 — 이벤트 계약-as-code](../adr/0024-event-contract-as-code.md) (페이로드 계약)
- [ADR 0022 — 이벤트 Schema Registry](../adr/0022-event-schema-registry.md) (Avro 전환 계획)
- ADR 0020 — order↔settlement DB 물리 분리 (프로젝션의 기원)
- [ADR 0017 — Kafka 컨슈머 DLT + Replay](../adr/0017-kafka-consumer-dlt-and-replay.md)
- [`../../SPEC.md`](../../SPEC.md) §5 토픽·매핑 · [`../../CLAUDE.md`](../../CLAUDE.md) 이벤트·멱등 절
