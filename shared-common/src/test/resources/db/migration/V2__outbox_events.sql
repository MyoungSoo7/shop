-- shared-common 테스트 전용 outbox_events 스키마.
-- 운영의 V28__create_outbox_events / V40__outbox_traceparent / V20260611110000__outbox_claim_columns
-- 는 order-service 모듈에 있어 이 모듈 클래스패스에 없다. claim 기반 동시성(OutboxClaimConcurrencyIT)
-- 검증을 위해 동일한 테이블 + claim 컬럼/인덱스를 opslab 스키마에 그대로 재현한다.
--
-- ClaimOutboxEventPort 의 네이티브 쿼리가 'opslab.outbox_events' 를 하드코딩하므로 스키마는 opslab.
-- (테스트는 hibernate.default_schema=opslab 로 엔티티의 무스키마 매핑도 opslab 로 해석되게 한다.)

CREATE SCHEMA IF NOT EXISTS opslab;

CREATE TABLE IF NOT EXISTS opslab.outbox_events (
    id                BIGSERIAL    PRIMARY KEY,
    aggregate_type    VARCHAR(50)  NOT NULL,
    aggregate_id      VARCHAR(64)  NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    event_id          UUID         NOT NULL,
    payload           JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count       INT          NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at      TIMESTAMP,
    trace_parent      VARCHAR(64),
    -- V20260611110000: 멀티워커 claim(리스) 컬럼
    claimed_at        TIMESTAMP,
    claimed_by        VARCHAR(64),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- 컨슈머 멱등 체크용 event_id 유니크
CREATE UNIQUE INDEX IF NOT EXISTS uq_outbox_event_id
    ON opslab.outbox_events (event_id);

-- 발행 후보(PENDING/FAILED) 폴링 최적화
CREATE INDEX IF NOT EXISTS idx_outbox_status_created
    ON opslab.outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

-- claim 후보 조회 최적화: PENDING 행을 created_at 순으로, claimed_at(리스) 필터와 함께
CREATE INDEX IF NOT EXISTS idx_outbox_pending_claim
    ON opslab.outbox_events (created_at, claimed_at)
    WHERE status = 'PENDING';
