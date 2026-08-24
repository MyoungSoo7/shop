-- V28: Transactional Outbox 패턴을 위한 outbox_events 테이블
--
-- 목적: 도메인 트랜잭션 커밋과 외부 이벤트 발행의 원자성을 보장한다.
-- 애플리케이션은 비즈니스 변경과 같은 트랜잭션 안에서 outbox_events 에
-- PENDING 상태 레코드를 쓴다. 별도 폴러가 PENDING 을 읽어
-- ApplicationEventPublisher / Kafka 로 발행 후 PUBLISHED 마킹한다.

CREATE TABLE IF NOT EXISTS opslab.outbox_events (
    id                BIGSERIAL PRIMARY KEY,
    aggregate_type    VARCHAR(50)  NOT NULL,      -- 예: "Payment", "Settlement"
    aggregate_id      VARCHAR(64)  NOT NULL,      -- 도메인 집합 ID (payment_id 등 문자열)
    event_type        VARCHAR(100) NOT NULL,      -- 예: "PaymentCaptured", "PaymentRefunded"
    event_id          UUID         NOT NULL,      -- 전역 고유 이벤트 ID — 컨슈머 측 멱등 키
    payload           JSONB        NOT NULL,      -- 이벤트 본문
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PUBLISHED / FAILED
    retry_count       INTEGER      NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at      TIMESTAMP,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- 폴러 조회용 인덱스: PENDING + 오래된 순
CREATE INDEX IF NOT EXISTS idx_outbox_status_created
    ON opslab.outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

-- 컨슈머 멱등 체크를 위한 event_id 유니크
CREATE UNIQUE INDEX IF NOT EXISTS uq_outbox_event_id
    ON opslab.outbox_events (event_id);

-- 특정 집합에 대한 이벤트 이력 조회
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON opslab.outbox_events (aggregate_type, aggregate_id);
