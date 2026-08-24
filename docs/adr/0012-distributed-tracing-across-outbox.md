# ADR 0012 — Outbox 경계 분산 트레이싱 (traceparent 보존)

- 상태: Accepted
- 일자: 2026-03-16

## 컨텍스트

결제→정산 흐름은 Transactional Outbox(ADR 0003) 위에서 **비동기 경계**를 두 번 넘는다:

```
[HTTP] 결제 요청 → [tx] capture + outbox_events INSERT
   → (폴러가 별도 스레드/인스턴스에서 비동기 발행) → Kafka
   → [tx] settlement-service 컨슈머가 정산 생성
```

분산 트레이싱(W3C Trace Context)은 동기 HTTP 호출 사이에서는 헤더(`traceparent`)로 자동
전파되지만, **Outbox 폴러 ↔ Kafka 컨슈머**는 원 요청 스레드와 분리된 비동기 경계라 trace
context 가 끊긴다. 그 결과 결제 span 과 정산 span 이 서로 다른 trace 로 쪼개져, "이 정산은
어느 결제에서 비롯됐나"를 단일 trace 로 추적할 수 없다. 금융 도메인에서 결제→정산 인과
가시성은 장애 분석·대사에 직접적이다.

핵심 제약: 폴러는 도메인 트랜잭션과 **다른 시점·다른 스레드**에서 발행한다. 따라서 발행
시점에는 원 요청의 trace context 가 이미 사라져 있다 — trace 를 **도메인 tx 시점에 붙잡아
영속화**해 두어야 한다.

## 결정

도메인 트랜잭션 시점의 W3C traceparent 를 outbox 레코드에 영속화하고, 폴러가 발행 시 이를
Kafka 헤더로 복원해 컨슈머가 같은 trace 에 합류하게 한다.

### 1. tx 시점 캡처 — `TraceContextCapture`

shared-common 의 `TraceContextCapture.captureCurrentTraceParent()` 가 micrometer-tracing
`Tracer.currentSpan()` 에서 traceId·spanId·sampled 를 읽어 W3C 형식
`00-{32hex traceId}-{16hex spanId}-{flags}` 문자열로 직렬화한다. `Tracer` 빈이 없는
트레이싱 비활성 환경에서는 `null` 을 반환해 기존 동작을 그대로 유지한다(`@Autowired(required=false)`).

`OutboxBackedEventPublisher` 가 도메인 tx 안에서 이 값을 캡처해 `OutboxEvent.pending(..., traceParent)`
로 이벤트에 동봉한다.

### 2. 영속화 — `outbox_events.trace_parent` (V40)

V40 마이그레이션이 `outbox_events` 에 `trace_parent VARCHAR(64)` 컬럼을 추가한다. trace 가
이벤트 행과 함께 같은 도메인 트랜잭션에서 커밋되므로, 발행이 한참 뒤·다른 인스턴스에서
일어나도 원 trace 가 보존된다. `OutboxEvent` 도메인은 `traceParent` 필드와 누락 호환
생성/재구성 오버로드를 갖는다(트레이싱 비활성 시 null).

### 3. 복원 — 폴러 → Kafka 헤더 → 컨슈머 자동 합류

폴러가 발행할 때 저장된 `trace_parent` 를 Kafka 메시지 헤더(`traceparent`)로 실어 보낸다.
컨슈머 측 트레이싱 계측이 헤더에서 context 를 추출해 **같은 traceId 아래 후속 span** 을
생성하므로, 결제→Outbox→Kafka→정산이 단일 trace 로 이어진다.

## 결과

### 좋아지는 점
- 비동기 경계를 넘어 결제→정산이 **단일 trace** 로 가시화 — 인과·지연 분석 일원화
- trace 가 이벤트와 같은 tx 에서 커밋되어, 지연·다중 인스턴스 발행에도 손실 없음
- 트레이싱 비활성 환경은 null 경로로 무변경 — 점진 도입 안전

### 트레이드오프 / 리스크
- outbox 스키마에 컬럼 1 개 추가(저장 비용 미미)
- 컨슈머 측 헤더 추출 계측이 누락되면 trace 가 다시 끊김 — 양끝 계측 일관성 필요
- traceparent 가 보존하는 것은 context 전파일 뿐, span 누락·샘플링 정책은 별도 관리

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **outbox 에 traceparent 영속 + 헤더 복원 (본 결정)** | ✓ | 비동기·지연·다중 인스턴스 발행에서도 trace 보존 |
| 발행 시점에 trace 새로 시작 | ✗ | 결제와 정산이 분리된 trace — 인과 단절 |
| ThreadLocal/MDC 만으로 전파 | ✗ | 폴러는 다른 스레드/인스턴스 — context 이미 소멸 |
| 페이로드(JSON)에 traceId 수동 삽입 | △ | 가능하나 표준 헤더 전파·자동 합류 이점 상실, 계약 오염 |

## 참조

- [0003 — Transactional Outbox 패턴](0003-transactional-outbox-pattern.md)
