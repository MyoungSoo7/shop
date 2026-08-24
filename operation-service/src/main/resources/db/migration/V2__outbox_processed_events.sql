-- V2: operation-service 자체 DB(lemuel_operation) 용 Outbox / 멱등 추적 테이블
--
-- operation-service 는 루트 패키지 스캔으로 shared-common 의 Outbox·멱등 인프라
-- (OutboxEventJpaEntity, ProcessedEventJpaEntity)가 JPA 엔티티로 잡히므로,
-- ddl-auto: validate 통과를 위해 동일 스키마를 자체 DB 에 생성한다 (loan-service V4 와 동일 패턴).
--
-- Phase 1 은 이벤트를 발행하지 않아 outbox_events 는 비어 있는 채 폴러만 유휴 동작하고,
-- Phase 2(실패 이벤트 구독)부터 processed_events 가 컨슈머 멱등 키로 실사용된다.

CREATE TABLE IF NOT EXISTS outbox_events (
    id                BIGSERIAL PRIMARY KEY,
    aggregate_type    VARCHAR(50)  NOT NULL,
    aggregate_id      VARCHAR(64)  NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    event_id          UUID         NOT NULL,      -- 전역 고유 — 컨슈머 측 멱등 키
    payload           JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PUBLISHED / FAILED
    retry_count       INTEGER      NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at      TIMESTAMP,
    trace_parent      VARCHAR(64),
    -- 멀티워커 claim(리스) 컬럼 — OutboxPublisherScheduler 의 FOR UPDATE SKIP LOCKED claim.
    -- ★ shared-common ClaimOutboxEventPort 네이티브 쿼리가 claimed_at/claimed_by 를 직접 참조하므로 필수.
    claimed_at        TIMESTAMP,
    claimed_by        VARCHAR(64),

    CONSTRAINT chk_ops_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- 폴러 조회용: PENDING/FAILED + 오래된 순
CREATE INDEX IF NOT EXISTS idx_ops_outbox_status_created
    ON outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

-- 컨슈머 멱등(프로듀서 측 중복 발행 방지)
CREATE UNIQUE INDEX IF NOT EXISTS uq_ops_outbox_event_id
    ON outbox_events (event_id);

CREATE INDEX IF NOT EXISTS idx_ops_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

-- claim 후보 조회 최적화: PENDING 행을 created_at 순으로, claimed_at(리스) 필터와 함께
CREATE INDEX IF NOT EXISTS idx_ops_outbox_pending_claim
    ON outbox_events (created_at, claimed_at)
    WHERE status = 'PENDING';

-- 컨슈머 측 멱등 추적: (consumer_group, event_id) 단위 처리 여부 기록 (Phase 2 실사용).
CREATE TABLE IF NOT EXISTS processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX IF NOT EXISTS idx_ops_processed_events_processed_at
    ON processed_events (processed_at);
