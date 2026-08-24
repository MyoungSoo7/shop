-- V40: Outbox 이벤트에 W3C traceparent 보존
--
-- 분산 트레이싱은 동기 호출 (HTTP / RPC) 에서는 헤더로 자동 전파되지만, Outbox 패턴은
-- 도메인 트랜잭션 ↔ 폴링 발행 ↔ Kafka 컨슈머 사이에 비동기 경계가 있어 trace context 가 끊긴다.
--
-- 해결: outbox 레코드에 발행 시점의 trace_parent (W3C Trace Context 형식) 를 영속화해두면
-- 폴러가 Kafka 헤더로 복원, 컨슈머가 같은 trace 안에서 후속 span 을 만든다 → 주문 → 결제 →
-- Outbox → Kafka → 정산 까지 단일 trace 로 가시화 가능.
--
-- traceparent 형식: "00-{32hex traceId}-{16hex spanId}-01" (총 55자)

ALTER TABLE opslab.outbox_events
    ADD COLUMN IF NOT EXISTS trace_parent VARCHAR(64);

COMMENT ON COLUMN opslab.outbox_events.trace_parent IS
    'W3C Trace Context. 도메인 트랜잭션 시점의 trace 를 보존해 비동기 발행 후에도 단일 trace 로 추적.';
