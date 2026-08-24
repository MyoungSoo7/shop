-- 운영 마이그레이션 V20260728010000__outbox_envelope_n4 의 테스트 재현 (DATA-STANDARD N4 봉투).
-- 각 서비스 모듈에 있는 운영 마이그레이션은 이 모듈 클래스패스에 없으므로 동일 DDL 을 opslab 에 반영한다.

ALTER TABLE opslab.outbox_events
    ADD COLUMN IF NOT EXISTS occurred_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS event_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS producer      VARCHAR(64);

UPDATE opslab.outbox_events
SET occurred_at = created_at AT TIME ZONE 'UTC'
WHERE occurred_at IS NULL;

ALTER TABLE opslab.outbox_events
    ALTER COLUMN occurred_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_occurred_at ON opslab.outbox_events (occurred_at);
