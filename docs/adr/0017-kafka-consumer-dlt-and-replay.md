# ADR 0017 — Kafka 컨슈머 DLT + Replay

- 상태: Accepted
- 일자: 2026-03-25

## 컨텍스트

settlement 는 order 가 발행한 도메인 이벤트(payment/order/user/product)를 Kafka 로 소비해 정산을
생성하고 프로젝션을 적재한다(ADR 0005, 0020). 이벤트 소비에는 두 종류의 실패가 섞인다:

- **일시적 실패**: DB lock timeout, IO error — 잠시 후 재시도하면 성공
- **독성 메시지(poison pill)**: JSON 파싱 불가, 음수 금액, 종료된 정산 재처리 등 — 몇 번을 재시도해도 실패

Spring Kafka 기본 동작은 `FixedBackOff(0, 9)` 로 즉시 9회 재시도 후 **조용히 skip** — 메시지가
사실상 유실된다. 더 나쁘게는, 독성 메시지가 같은 파티션의 후속 메시지를 **stall** 시켜 정산 SLA 를
무너뜨린다. 금융 이벤트는 유실도 stall 도 허용되지 않는다.

또한 발행 측(Outbox)에서도 영구 실패가 발생한다 — 외부 시스템 장애로 재시도 한계를 넘긴 이벤트가
조용히 사라지면 안 된다.

## 결정

이벤트 파이프라인 양끝(컨슈머·Outbox)에 **DLT/DLQ 격리 + 운영자 Replay** 를 둔다.

### 1. 컨슈머 측 — DefaultErrorHandler + DLT

shared-common 의 `KafkaConsumerErrorHandlingConfig` 가 `DefaultErrorHandler` 를 구성한다. 원래는
서비스마다 `KafkaErrorHandlerConfig` 를 복붙했으나(5벌), 그 사본 문화 때문에 나중에 추가된
서비스는 배선 자체가 누락돼 기본 핸들러 `FixedBackOff(0, 9)` 로 떨어졌다 — 기본 핸들러는 재시도
소진 후 메시지를 조용히 skip 하므로 사실상 유실이다. 배선을 한 벌로 모으고, 누락은 `guard.mjs` 의
`KAFKA-DLQ` 규칙이 기계로 차단한다:

- 일시적 예외 → `FixedBackOff(2s, 3회)` 재시도
- 독성 예외(`JsonProcessingException`, `IllegalArgumentException`, `IllegalStateException`)는
  `addNotRetryableExceptions` 로 **재시도 없이 즉시 DLT**
- 서비스별 도메인 타입 예외(account 의 `AccountDomainException` 등)는 `NonRetryableConsumerExceptions`
  빈으로 기여해 같은 즉시-DLT 목록에 붙인다 — 계약 위반은 재시도로 복구되지 않는다
- 재시도 한계 도달 → `DeadLetterPublishingRecoverer` 가 원본 record 를 `<topic>.DLT` 로 복사 후 ack
  → 같은 파티션의 후속 메시지는 정상 처리(stall 방지)

DLT recoverer 는 `kafka_dlt-*` 헤더(원본 토픽/파티션/오프셋/예외 FQCN/스택트레이스)를 자동 부여하고,
원본 `event_id`·`traceparent` 헤더는 패스스루한다 → 사후 추적 + replay 시 멱등 보장. 메트릭
`settlement.kafka.dlt.published` 로 알람 임계(예: 10/min)를 건다(접두는 `spring.application.name` 의
`lemuel-` 을 떼어 유도하므로 서비스마다 기존 알람 이름이 그대로 유지된다). 컨슈머는
`ConditionalOnProperty(app.kafka.enabled=true)` 라 Kafka 비활성 환경에서는 빈 자체가 안 만들어진다.

shared-common 을 의존하지 않는 폴리글랏 standalone(`notification-service`, Kotlin/Boot 3.x)은 같은
계약을 자기 언어로 구현한다(`KafkaErrorHandlingConfig`). ack 모드만 다르다 — 그 서비스 리스너에는
`Acknowledgment` 파라미터가 없어 `RECORD` 를 쓴다(수동 모드면 오프셋이 영원히 커밋되지 않는다).

### 2. Outbox 측 — retryCount 한계 → DLQ

발행 측은 `OutboxBatchEventPublisher` 가 비동기 배치 발행 후 실패를 `OutboxEvent.markFailed` 로
retryCount++ 하고, **한계(10회) 초과로 FAILED 전이되는 순간 정확히 한 번** DLQ 로 발행한다
(`PublishDlqEventPort`). DLQ 발행 실패가 배치 finalize 를 막지 않으며, 그 경우 이벤트는 FAILED 로
남아 콘솔에서 회수된다. 메트릭 `outbox.dlq.published`.

### 3. 운영자 Admin REST

- **컨슈머 DLT**: `DlqAdminController` (`/admin/dlq`, ROLE_ADMIN) — `GET /inspect`(commit 없이
  read-only 검사), `POST /replay`(원본 토픽 republish). 모든 작업은 audit_logs 에 기록.
  `DlqReplayService` 는 요청마다 일회용 컨슈머 그룹을 만들어 read→seek→close(장기 lag 없음),
  `x-replay-count` 헤더로 5회 이상 replay 를 차단(무한 루프 방지), `kafka_dlt-*` 헤더는 제외하고
  원본 페이로드/`event_id` 만 재발행한다. 재처리는 `processed_events(group, event_id)` 멱등으로 안전.
- **Outbox DLQ**: shared-common 의 `OutboxAdminController` (`/admin/outbox`) — `GET /dlq`(FAILED 목록),
  `POST /dlq/{eventId}/retry`(PENDING 복원 + retryCount 0), `POST /dlq/{eventId}/skip`(사유 필수 +
  `[SKIPPED]` 영구 기록). 양 서비스가 공유하므로 shared-common 에 둔다.

## 결과

### 좋아지는 점

- 독성 메시지가 정산 SLA 를 무너뜨리지 못함(즉시 DLT + 파티션 진행)
- 이벤트 유실 0 — 컨슈머/발행 양끝의 영구 실패가 DLT/DLQ 로 보존되어 운영자가 회수
- replay 멱등(`processed_events` + `event_id` 패스스루)으로 재처리가 안전
- DLT 헤더로 원본 컨텍스트·예외 원인을 운영자에게 actionable 하게 노출

### 트레이드오프 / 리스크

- DLT 토픽 운영(보존·모니터링·정리)이 추가 운영 부담
- 잘못된 핫픽스 없이 무한 replay 하면 같은 메시지가 DLT↔원본을 왕복(5회 제한으로 완화)
- 컨슈머/Outbox 두 경로의 DLQ 가 따로라 운영자가 양쪽을 모두 봐야 함

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **Spring Kafka 기본(즉시 9회 후 skip)** | ✗ | 조용한 유실 + 독성 메시지 stall — 금융 부적합 |
| **무한 재시도(skip 없음)** | ✗ | 독성 메시지가 파티션 영구 stall |
| **재시도 + DLT/DLQ + 운영자 replay (본 결정)** | ✓ | 일시 실패 흡수 + 영구 실패 격리 + 멱등 회수 |

## 참조

- [0003 — Transactional Outbox 패턴](0003-transactional-outbox-pattern.md)
