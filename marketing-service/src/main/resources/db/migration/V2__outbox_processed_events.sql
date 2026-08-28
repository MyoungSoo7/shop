-- =====================================================================================
-- V2: marketing-service 자체 DB(lemuel_marketing) 의 Outbox / 멱등 추적 인프라
--
-- 루트 패키지 스캔이라 shared-common 의 OutboxEventJpaEntity·ProcessedEventJpaEntity 가
-- 엔티티로 잡힌다. ddl-auto: validate 이므로 이 두 테이블이 없으면 기동 자체가 실패한다.
--
-- 형제 서비스는 이 스키마를 두 번에 나눠 만들었다(V2 뼈대 + 나중에 봉투 컬럼 추가). 신규
-- 서비스는 백필할 과거 행이 없으므로 한 번에 최종 형태로 만든다 — 특히 occurred_at 은
-- 처음부터 NOT NULL 로 둘 수 있다(기존 서비스는 컬럼 추가 → 백필 → NOT NULL 3단계였다).
--
-- 이 서비스에서 outbox 가 실어 나르는 것은 lemuel.marketing.reward_requested 하나이고,
-- processed_events 는 lemuel.point.granted 를 받아 보상을 CONFIRMED 로 넘길 때 쓰인다.
-- 두 방향 모두 at-least-once 라서, 중복 수신을 막는 것은 이 테이블이지 컨슈머 코드가 아니다.
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
    -- occurred_at   사건이 실제로 일어난 시각(UTC). created_at 은 행 생성 시각이라 재처리하면 밀린다.
    -- event_version 페이로드 스키마 버전. 소비측 분기 근거. 신규는 1.
    -- producer      발행 서비스 이름(spring.application.name).
    occurred_at       TIMESTAMPTZ  NOT NULL,
    event_version     INTEGER      NOT NULL DEFAULT 1,
    producer          VARCHAR(64),

    CONSTRAINT chk_mkt_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- 폴러 조회용: PENDING/FAILED + 오래된 순
CREATE INDEX IF NOT EXISTS idx_mkt_outbox_status_created
    ON outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

-- 프로듀서 측 중복 발행 방지
CREATE UNIQUE INDEX IF NOT EXISTS uq_mkt_outbox_event_id
    ON outbox_events (event_id);

CREATE INDEX IF NOT EXISTS idx_mkt_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

-- claim 후보 조회 최적화: PENDING 행을 created_at 순으로, claimed_at(리스) 필터와 함께
CREATE INDEX IF NOT EXISTS idx_mkt_outbox_pending_claim
    ON outbox_events (created_at, claimed_at)
    WHERE status = 'PENDING';

-- 사건 시각 기준 조회(지연 측정·기간별 재처리)
CREATE INDEX IF NOT EXISTS idx_mkt_outbox_occurred_at
    ON outbox_events (occurred_at);

-- 컨슈머 측 멱등 추적: (consumer_group, event_id) 단위 처리 여부.
CREATE TABLE IF NOT EXISTS processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX IF NOT EXISTS idx_mkt_processed_events_processed_at
    ON processed_events (processed_at);
