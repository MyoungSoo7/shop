-- V1: operation-service 자체 DB(lemuel_operation) — 인시던트 코어
--
-- 인시던트는 Alertmanager 알람(Phase 1) / 자체 이상 탐지(Phase 3) / 수동 등록의
-- 공통 라이프사이클 컨테이너. correlation_key(Alertmanager fingerprint)로
-- 반복 알람을 활성 인시던트 1건에 병합한다.
-- 설계: docs/design/operation-service-phase1.md

CREATE TABLE incidents (
    id                BIGSERIAL PRIMARY KEY,
    correlation_key   VARCHAR(128) NOT NULL,   -- Alertmanager fingerprint (source 별 유일 식별자)
    source            VARCHAR(20)  NOT NULL,   -- ALERTMANAGER / ANOMALY / MANUAL
    category          VARCHAR(30)  NOT NULL,   -- SignalCategory (labels.component 매핑)
    severity          VARCHAR(10)  NOT NULL,   -- CRITICAL / WARNING / INFO
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    title             VARCHAR(200) NOT NULL,   -- alertname
    description       TEXT,                    -- annotations.summary + description
    service           VARCHAR(50),             -- labels.component 원본 보존
    labels            JSONB        NOT NULL DEFAULT '{}',
    annotations       JSONB        NOT NULL DEFAULT '{}',
    first_seen_at     TIMESTAMPTZ  NOT NULL,   -- alert startsAt
    last_seen_at      TIMESTAMPTZ  NOT NULL,   -- 마지막 firing 수신 시각
    occurrence_count  INTEGER      NOT NULL DEFAULT 1,  -- firing 반복 수신 횟수
    last_refire_logged_at TIMESTAMPTZ,         -- 마지막 REFIRED 타임라인 기록 시각 (30분 억제 기준)
    acknowledged_at   TIMESTAMPTZ,
    acknowledged_by   VARCHAR(100),
    resolved_at       TIMESTAMPTZ,
    resolved_by       VARCHAR(100),            -- 'alertmanager' = 자동 해제
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version           BIGINT       NOT NULL DEFAULT 0,  -- @Version (운영자 조작 경쟁 방지)

    CONSTRAINT chk_incident_status
        CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'FALSE_POSITIVE')),
    CONSTRAINT chk_incident_severity
        CHECK (severity IN ('CRITICAL', 'WARNING', 'INFO')),
    CONSTRAINT chk_incident_source
        CHECK (source IN ('ALERTMANAGER', 'ANOMALY', 'MANUAL'))
);

-- ★ 핵심 불변식: 같은 (source, correlation_key) 의 "활성" 인시던트는 최대 1건.
--   동시 webhook 경쟁은 이 인덱스 위반 → 애플리케이션이 catch 후 갱신 경로로 폴백(멱등).
--   resolved 후 재발(firing)은 새 인시던트로 생성된다 (reopen 없음 — correlation_key 로 이력 조회).
CREATE UNIQUE INDEX uq_incident_active
    ON incidents (source, correlation_key)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');

-- 목록 조회 (상태/카테고리 필터 + 최신순)
CREATE INDEX idx_incident_status_category
    ON incidents (status, category, last_seen_at DESC);

-- 요약 통계 (기간 필터: summary API window)
CREATE INDEX idx_incident_first_seen ON incidents (first_seen_at);

CREATE TABLE incident_timeline (
    id           BIGSERIAL PRIMARY KEY,
    incident_id  BIGINT       NOT NULL REFERENCES incidents(id),
    event_type   VARCHAR(30)  NOT NULL,
    actor        VARCHAR(100) NOT NULL,  -- 운영자 username 또는 'alertmanager'
    note         TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_timeline_event_type CHECK (event_type IN
        ('OPENED', 'REFIRED', 'ACKNOWLEDGED', 'RESOLVED', 'AUTO_RESOLVED', 'FALSE_POSITIVE', 'COMMENT'))
);

CREATE INDEX idx_timeline_incident ON incident_timeline (incident_id, created_at);
