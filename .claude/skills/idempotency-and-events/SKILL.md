---
name: idempotency-and-events
description: Outbox 발행·Kafka 컨슈머·멱등성 3단 방어 규칙. 이벤트 발행/구독 코드를 작성하거나 중복 처리 버그를 조사할 때 로드.
---

# 이벤트·멱등성 규칙 (ADR 0003, 0017)

## 발행 — 반드시 Outbox 경유

- 비즈니스 tx 안에서 `kafkaTemplate.send()` 직접 호출 금지. 같은 DB tx 에서
  `outbox_events` INSERT (event_id UUID) → 멀티워커 폴러가 `FOR UPDATE SKIP LOCKED` claim 후 발행.
- 이유: DB 커밋과 발행의 원자성. 직접 발행은 "커밋 실패했는데 이벤트는 나감" 사고를 만든다.
- 관측: `outbox.pending.count`(적체), `outbox.failed.count`, `outbox.dlq.published` — MCP `outbox_status`.

## 구독 — 멱등 체크는 컨슈머 코드의 첫 줄

새 컨슈머 필수 골격:

```java
@KafkaListener(topics = "...", groupId = GROUP)
public void on(Event e) {
    if (!processedEventPort.markIfNew(GROUP, e.eventId())) return; // ← 멱등 체크 (같은 tx)
    // ... 비즈니스 로직
}
```

- `processed_events` PK = `(consumer_group, event_id)` — 그룹별 독립 멱등.
- 멱등 체크와 비즈니스 로직은 **같은 DB 트랜잭션** — 체크만 커밋되고 로직이 롤백되면 이벤트 유실이다.

## 3단 멱등 방어 (어느 층이 빠졌는지로 버그 위치 특정)

| 층 | 방어 | 뚫리면 생기는 증상 |
|---|---|---|
| 1 | `outbox_events.event_id UUID UNIQUE` | 같은 이벤트 중복 발행 |
| 2 | `processed_events PK (consumer_group, event_id)` | 컨슈머 중복 처리 |
| 3 | `settlements.payment_id UNIQUE` | 최후 방어 — 중복 정산 생성 시 DB 제약 위반 |

중복 정산 버그 조사 시: 3층 제약 위반 로그가 있으면 1·2층이 뚫린 것 — event_id 생성 위치와
컨슈머 tx 경계부터 확인하라.

## 신규 컨슈머 체크리스트 (돈이 걸린 토픽이면 예외 없음)

**2층은 그룹 ID 에 묶여 있다.** `processed_events` PK 가 `(consumer_group, event_id)` 이므로
**그룹 ID 를 바꾸는 순간 2층 방어가 통째로 무효**가 된다. 전 서비스가 `auto-offset-reset: earliest`
이라 새 그룹은 보존기간(7일) 안의 이벤트를 **전량 재처리**한다. 즉 yml 한 줄 수정이 "지난 7일치 회계
이벤트 재생" 스위치다. 이때 남는 것은 3층뿐이다.

새 컨슈머를 붙이기 전에 답할 것:

1. **3층(도메인 자연키 UNIQUE)이 있는가?** 없으면 그룹 ID 변경·DLT 리플레이가 곧 이중 계상이다.
   현행 예: deposit `UNIQUE(account_id, entry_type, reference_type, reference_id, offset_sequence)`,
   account `(source_topic, ref_type, ref_id)`, settlement `settlements.payment_id`.
   금액을 쓰거나 원장에 적는 컨슈머인데 3층이 없다면, **먼저 마이그레이션으로 제약을 넣고** 시작한다.
2. **그룹 ID 가 `lemuel-<모듈명>` 인가?** 다른 서비스와 같은 그룹을 쓰면 카프카가 둘을 한 그룹으로 보고
   파티션을 나눠 줘, 한쪽이 가져간 메시지는 다른 쪽에 오지 않고 오프셋까지 공유되어 **조용히 유실**된다.
   guard `KAFKA-GROUP-OWNER` 가 강제한다(order-service 가 `lemuel-settlement` 을 들고 있던 실제 사례).
3. **팬아웃인가 분산인가?** 같은 토픽을 여러 서비스가 각자 처리해야 하면 그룹 ID 가 서로 **달라야** 한다
   (예: `lemuel.settlement.confirmed` 를 account·deposit·investment·loan 이 각각 소비).
   같은 서비스를 N대로 늘려 처리량을 올리는 것이라면 그룹 ID 는 **같아야** 한다.
4. **`auto-offset-reset` 이 목적에 맞는가?** 과거분까지 필요하면 `earliest`(기본), 지금부터면 `latest`
   (operation-service 가 후자 — 사유를 yml 주석에 남겨 뒀다).
5. **DLT 배선이 닿는가?** guard `KAFKA-DLQ` 가 강제한다. 안 닿으면 Spring 기본 핸들러가 재시도 소진
   메시지를 조용히 skip 한다(= 사실상 유실).
6. **토픽이 카탈로그에 있는가?** 없으면 브로커 기본값으로 자동생성되어 파티션이 코드 밖에서 정해진다
   (ADR 0035). `kafka-topic-gate`·`kafka-publisher-gate` 가 강제한다.

## 실패 처리 — DLT + 리플레이 (ADR 0017)

- 컨슈머 예외는 재시도 후 DLT 토픽으로. DLT 적체는 "조용한 데이터 유실" — 정기 확인 대상.
- 리플레이 시 멱등 체크 덕에 이미 처리된 이벤트는 무해하게 스킵된다 — 이것이 멱등을 강제하는 이유.
- 스키마 변경은 하위호환만 허용 (ADR 0022) — 필드 삭제/타입 변경은 신규 토픽 버전으로.

## 로컬 주의사항

- rpk 로 직접 produce 테스트 시 `-z none` 필수 (Redpanda 이미지에 snappy 네이티브 없음).
