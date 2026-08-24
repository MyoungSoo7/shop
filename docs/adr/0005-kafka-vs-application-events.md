# ADR 0005 — Kafka vs Spring ApplicationEvents

- 상태: Accepted
- 일자: 2026-01-28

## 컨텍스트

도메인 변경을 다른 컴포넌트로 전파하는 메커니즘이 둘 있다:

- **Spring `ApplicationEventPublisher`** — 같은 JVM·같은 트랜잭션 안에서 동기/`@TransactionalEventListener`
  로 전달되는 in-process 이벤트. 추가 인프라 0.
- **Kafka** — 프로세스/서비스 경계를 넘는 영속 로그 기반 비동기 메시징.

MSA 분리 후 order → settlement(정산 생성), settlement → loan(`settlement.created/confirmed`)
처럼 **서비스 경계를 넘는** 전파가 생겼다. 동시에 한 서비스 안에서는 결제 캡처 후 후속 처리 같은
**프로세스 내부** 전파도 필요하다. 둘을 같은 메커니즘으로 묶으면 결합도·운영비가 어긋난다 —
서비스 내부 알림에 Kafka 를 쓰면 과하고, 서비스 간 전달에 in-process 이벤트를 쓰면 경계를 넘지
못한다.

## 결정

전파 범위에 따라 메커니즘을 나눈다.

### 1. 서비스 간 통신 → Kafka (Outbox 기반)

서비스 경계를 넘는 모든 이벤트는 ADR 0003 의 Transactional Outbox 를 통해 Kafka 로 발행한다.
`KafkaOutboxPublisher` 가 `app.kafka.enabled=true` 일 때 활성화되어 토픽
`lemuel.<aggregate>.<event_snake>`(예: `lemuel.payment.captured`)로 보낸다. 컨슈머는
`processed_events` PK 멱등으로 at-least-once 중복을 흡수한다.

이유:

- **Temporal decoupling** — 컨슈머(settlement)가 내려가 있어도 프로듀서(order)는 발행을 계속하고,
  컨슈머가 복구되면 로그를 따라잡는다. 동기 호출처럼 다운스트림 장애가 업스트림으로 전파되지 않는다.
- **독립 배포** — order 와 settlement 가 서로의 코드/가용성에 묶이지 않는다(README 의 코드 의존 0 경계).
- **at-least-once + 멱등** — 영속 로그라 유실되지 않고, 중복은 멱등 방어(ADR 0003 3단)로 무해화.

### 2. 서비스 내부 → 가능 시 ApplicationEvents

같은 JVM 안의 전파는 Spring `ApplicationEventPublisher` 로 처리한다. Kafka 가 비활성
(`app.kafka.enabled=false`, 기본)일 때 `ApplicationEventOutboxPublisher` 가
`PublishExternalEventPort` 의 폴백 구현으로 등록되어 `publisher.publishEvent(event)` 로
in-process 전달한다 — Kafka 인프라 없이도 outbox 흐름이 로컬/단일 서비스에서 동작한다.

이유: 브로커 라운드트립 없이 빠르고, 트랜잭션 경계와 자연스럽게 어울리며, 운영 인프라가 0 이다.
단 경계를 넘을 가능성이 생기면 Kafka 경로로 승격한다.

## 결과

### 좋아지는 점

- 서비스 간 결합도를 낮춰(temporal decoupling) 다운스트림 장애 격리 + 독립 배포 확보.
- 같은 `PublishExternalEventPort` 추상 뒤에서 Kafka/ApplicationEvents 를 프로퍼티로 전환 →
  로컬·테스트는 인프라 없이, 운영은 Kafka 로 동일 코드 경로 사용.
- 내부 전파는 in-process 로 가볍게 유지해 불필요한 브로커 비용 회피.

### 트레이드오프 / 리스크

- Kafka 경로는 at-least-once → 컨슈머 멱등 구현 필수(누락 시 중복 처리).
- 이벤트 순서는 파티션(=aggregateId key) 단위로만 보장 — 전역 순서 가정 금지.
- 두 메커니즘 공존으로, 전파 범위 판단을 잘못하면(내부에 Kafka / 경계에 in-process) 결합도·비용이 어긋남.
- in-process 폴백과 Kafka 의 전달 의미(동기 vs at-least-once)가 달라 환경 간 미묘한 동작 차이 가능.

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **모든 전파를 ApplicationEvents 로** | ✗ | 서비스 경계를 넘지 못함, temporal decoupling·독립 배포 불가 |
| **모든 전파를 Kafka 로(내부 포함)** | ✗ | 서비스 내부 알림에 과한 브로커 비용·지연 |
| **서비스 간 동기 REST 호출** | ✗ | 다운스트림 장애가 업스트림으로 전파, 시간 결합 강함 |
| **Kafka(서비스 간) + ApplicationEvents(내부) (본 결정)** | ✓ | 범위별 적정 결합도, 동일 포트 추상으로 전환 용이 |

## 참조

- [0003 — Transactional Outbox 패턴](0003-transactional-outbox-pattern.md)
- [0022 — 이벤트 Schema Registry](0022-event-schema-registry.md)
