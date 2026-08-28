-- =====================================================================================
-- V2: partner-service 자체 DB(lemuel_partner) 의 Outbox / 멱등 추적 인프라
--
-- 루트 패키지 스캔이라 shared-common 의 OutboxEventJpaEntity·ProcessedEventJpaEntity 가
-- 엔티티로 잡힌다. ddl-auto: validate 이므로 이 두 테이블이 없으면 기동 자체가 실패한다.
--
-- ★ outbox_events 는 이 서비스에서 **영원히 비어 있을 수 있다.** partner-service 는 토픽을
--   하나도 발행하지 않기 때문이다. 그런데도 만드는 이유는 두 가지다.
--     1) validate 가 엔티티에 대응하는 테이블을 요구한다(없으면 부팅 실패).
--     2) 폴러 빈을 살려 두어야 shared-common 의 DLT 배선(KafkaConsumerErrorHandlingConfig)이
--        같이 살아난다. 구독만 하는 서비스에도 DLT 는 필요하다 — 오히려 더 필요하다.
--   빈 테이블을 2초마다 훑는 비용은 인덱스 하나짜리 조회 한 번이다.
--
-- processed_events 가 이 서비스의 진짜 일꾼이다. 9개 토픽을 받아 프로젝션을 갱신하는데,
-- 전달은 at-least-once 라서 중복 수신을 막는 것은 이 테이블이지 컨슈머 코드가 아니다.
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

    CONSTRAINT chk_ptn_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_ptn_outbox_status_created
    ON outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE UNIQUE INDEX IF NOT EXISTS uq_ptn_outbox_event_id
    ON outbox_events (event_id);

CREATE INDEX IF NOT EXISTS idx_ptn_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS idx_ptn_outbox_pending_claim
    ON outbox_events (created_at, claimed_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_ptn_outbox_occurred_at
    ON outbox_events (occurred_at);

-- 컨슈머 측 멱등 추적: (consumer_group, event_id) 단위 처리 여부.
CREATE TABLE IF NOT EXISTS processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX IF NOT EXISTS idx_ptn_processed_events_processed_at
    ON processed_events (processed_at);
