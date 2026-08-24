-- V29: 컨슈머 측 멱등 추적 테이블
--
-- Kafka 컨슈머가 같은 event_id 를 두 번 수신(리밸런싱·재처리·at-least-once 의 본질)
-- 하더라도 비즈니스 부작용(정산 중복 생성, 환불 이중 반영)을 막기 위해
-- consumer_group × event_id 단위로 처리 여부를 기록한다.
--
-- 사용 패턴:
--   1) 컨슈머는 비즈니스 처리 전에 (group, event_id) 삽입 시도
--   2) unique 충돌 → 이미 처리됨 → ack 만 하고 return
--   3) 정상 삽입 → 비즈니스 처리 → 같은 트랜잭션 커밋

CREATE TABLE IF NOT EXISTS opslab.processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);

-- 오래된 이벤트 삭제(운영 유지 관리) 용 인덱스
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at
    ON opslab.processed_events (processed_at);
