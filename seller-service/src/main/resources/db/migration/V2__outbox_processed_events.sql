-- =====================================================================================
-- V2: seller-service 자체 DB(lemuel_seller) 의 Outbox / 멱등 추적 인프라
--
-- 루트 패키지 스캔이라 shared-common 의 OutboxEventJpaEntity·ProcessedEventJpaEntity 가
-- 엔티티로 잡힌다. ddl-auto: validate 이므로 이 두 테이블이 없으면 기동 자체가 실패한다.
--
-- ★ partner-service 의 같은 파일과 달리 여기 outbox_events 는 **실제로 쓰인다.** 셀러가
--   신청서를 올리고 운영자가 승인하면 그 사실이 카탈로그에 반영돼야 하는데, 이 서비스는 남의
--   원장에 직접 쓰지 않는다. 승인 트랜잭션이 자기 테이블(product_submissions)의 상태 전이와
--   발행 레코드를 **한 트랜잭션에 함께** 커밋하고, 폴러가 그 뒤에 브로커로 옮긴다.
--   직접 발행하면 "승인은 됐는데 요청은 안 나갔다" 가 재시도 불가능한 상태로 남는다.
--
-- processed_events 는 반대 방향을 막는다. 이 서비스는 조직·주문·상품 사본을 이벤트로 받는데
-- 전달이 at-least-once 라서, 중복 수신을 막는 것은 이 테이블이지 컨슈머 코드가 아니다.
-- =====================================================================================

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
    -- 멀티워커 claim(리스) 컬럼 — shared-common ClaimOutboxEventPort 의 FOR UPDATE SKIP LOCKED
    -- 네이티브 쿼리가 이 두 컬럼을 직접 참조한다. 없으면 폴러가 첫 주기에 SQL 오류로 죽는다.
    claimed_at        TIMESTAMP,
    claimed_by        VARCHAR(64),
    -- 이벤트 봉투 표준 (DATA-STANDARD N4)
    occurred_at       TIMESTAMPTZ  NOT NULL,
    event_version     INTEGER      NOT NULL DEFAULT 1,
    producer          VARCHAR(64),

    CONSTRAINT chk_slr_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_slr_outbox_status_created
    ON outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE UNIQUE INDEX IF NOT EXISTS uq_slr_outbox_event_id
    ON outbox_events (event_id);

CREATE INDEX IF NOT EXISTS idx_slr_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS idx_slr_outbox_pending_claim
    ON outbox_events (created_at, claimed_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_slr_outbox_occurred_at
    ON outbox_events (occurred_at);

-- 컨슈머 측 멱등 추적: (consumer_group, event_id) 단위 처리 여부.
CREATE TABLE IF NOT EXISTS processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX IF NOT EXISTS idx_slr_processed_events_processed_at
    ON processed_events (processed_at);
