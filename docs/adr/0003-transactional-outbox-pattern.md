# ADR 0003 — Transactional Outbox 패턴

- 상태: Accepted
- 일자: 2026-01-20

## 컨텍스트

order-service 가 결제 캡처 같은 도메인 변경을 커밋한 뒤 settlement-service 로 이벤트를 전달해야
한다(`PaymentCaptured` → 정산 생성). 단순하게 "DB 커밋 후 `kafkaTemplate.send()`" 로 발행하면
**dual-write 문제**가 생긴다:

- DB 커밋은 성공했는데 Kafka 발행 직전 프로세스가 죽으면 → 이벤트 유실(정산 누락).
- Kafka 발행은 성공했는데 DB 커밋이 롤백되면 → 유령 이벤트(존재하지 않는 결제의 정산).

두 저장소(DB, 브로커)에 걸친 원자성은 분산 트랜잭션 없이는 보장되지 않으며, 2PC 는 가용성·성능
비용이 크다. 금융 도메인에서 이벤트 유실/중복은 곧 금액 오류이므로 다른 접근이 필요하다.

## 결정

**Transactional Outbox 패턴**을 도입한다. 도메인 변경과 같은 DB 트랜잭션 안에서 `outbox_events`
테이블에 이벤트를 INSERT 하고, 별도 폴러가 이를 읽어 Kafka 로 발행한다. 공통 인프라는
`shared-common.common.outbox` 에 위치한다.

### 1. 같은 트랜잭션으로 outbox 적재

도메인 서비스의 `@Transactional` 안에서 아웃바운드 어댑터가 outbox 레코드를 쓴다. 예:
`order-service/.../payment/adapter/out/event/OutboxBackedEventPublisher` 가
`PublishEventPort` 를 구현하며, payload 를 `ObjectMapper` 로 직렬화해
`OutboxEvent.pending(...)` 으로 만들고 `SaveOutboxEventPort.save()` 한다. 비즈니스 변경과
outbox INSERT 가 **하나의 커밋**으로 원자화되므로 dual-write 가 원천 차단된다.

`OutboxEvent`(순수 POJO)는 `eventId`(UUID), `aggregateType/aggregateId`, `eventType`,
`payload`(JSON), `status`, `traceParent`(W3C trace context)를 보유한다.

### 2. 별도 폴러가 Kafka 발행

`OutboxPublisherScheduler` 가 `fixedDelay`(기본 2000ms, `app.outbox.polling-delay-ms`)로
PENDING 레코드를 **`FOR UPDATE SKIP LOCKED`** 로 claim(`ClaimOutboxEventPort.claimPending`)한
뒤 `OutboxBatchEventPublisher` 로 배치 발행한다. SKIP LOCKED 덕분에 여러 인스턴스가 동시에
폴링해도 서로 겹치지 않는 행만 가져가, 인스턴스 수만큼 처리량이 늘어난다(ShedLock 직렬화 불필요).
claim 리스(`claimed_at`, 기본 1분)가 찍혀 발행 전 워커가 죽어도 다른 워커가 회수한다.

발행은 `PublishExternalEventPort` 구현으로 분기한다: `app.kafka.enabled=true` →
`KafkaOutboxPublisher`(토픽 `lemuel.<aggregate>.<event_snake>`, key=aggregateId 로 순서 보장),
기본값 → `ApplicationEventOutboxPublisher`(in-process 폴백).

### 3. 3단 멱등 방어

at-least-once 발행이므로 컨슈머는 중복 수신을 전제로 멱등하게 처리한다:

1. **`outbox_events.event_id UUID UNIQUE`** — 프로듀서 측 동일 이벤트 중복 적재 방지(`OutboxEvent`
   가 `UUID.randomUUID()` 부여).
2. **`processed_events` PK `(consumer_group, event_id)`** — 컨슈머 측 중복 처리 차단
   (`ProcessedEventJpaEntity` 의 `@EmbeddedId ProcessedEventId`). 같은 그룹이 같은 event_id 를
   두 번 보면 PK 충돌로 스킵.
3. **비즈니스 UNIQUE** — 최종 방어선. 예: `settlements.payment_id UNIQUE` 로 한 결제당 정산
   1건만 생성.

## 결과

### 좋아지는 점

- dual-write 문제 제거 — 이벤트와 도메인 변경이 단일 커밋으로 원자화(유령/유실 이벤트 0).
- 3단 멱등으로 at-least-once 발행에도 중복 부작용 없음(금액 정확성 보장).
- SKIP LOCKED 멀티워커로 ShedLock 단일화 병목 없이 수평 확장.
- `traceParent` 영속화로 비동기 폴러 경계에서도 단일 분산 trace 유지.

### 트레이드오프 / 리스크

- 폴링 주기만큼 발행 지연(기본 2s) — 강한 실시간성이 필요한 경로엔 부적합.
- outbox 테이블 적재·정리(보존 정책) 운영 부담 추가.
- 컨슈머 멱등을 매번 구현해야 함(processed_events 체크 누락 시 중복 처리 위험).
- payload 가 스키마 없는 JSON 이라 계약 드리프트 방어가 약함 → ADR 0022 에서 보강.

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **커밋 후 직접 Kafka send** | ✗ | dual-write — 유실/유령 이벤트 발생 |
| **2PC / XA 분산 트랜잭션** | ✗ | 가용성·성능 비용 큼, 브로커 XA 운영 복잡 |
| **CDC (Debezium 등) 로 binlog 캡처** | △ | 애플리케이션 무침습이나 인프라(커넥터) 추가·운영 부담, 페이로드 형태 제어 약함 |
| **Transactional Outbox + 폴러 (본 결정)** | ✓ | 단일 커밋 원자성 + 앱이 페이로드 제어, 멀티워커로 확장 |

## 참조

- [0005 — Kafka vs ApplicationEvents](0005-kafka-vs-application-events.md)
- [0009 — Spring Boot 4 마이그레이션 + 모듈 분리](0009-boot4-migration-module-split.md)
- [0022 — 이벤트 Schema Registry](0022-event-schema-registry.md)
