-- V20260611110000: Outbox 멀티워커 발행을 위한 claim(리스) 컬럼
--
-- 목적: 단일 인스턴스(ShedLock)로 직렬화되던 outbox 폴링을 다중 인스턴스 병렬 발행으로 전환한다.
-- 각 워커는 SELECT ... FOR UPDATE SKIP LOCKED 로 서로 겹치지 않는 PENDING 행을 claim 하고,
-- claimed_at 으로 리스를 표시한다. 워커가 발행 전 죽으면 리스(claimed_at)가 만료되어
-- 다른 워커가 다시 가져간다 — 별도 reaper 없이 자동 복구.

ALTER TABLE opslab.outbox_events
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(64);

-- 클레임 후보 조회 최적화: PENDING 행을 created_at 순으로, claimed_at 필터와 함께.
CREATE INDEX IF NOT EXISTS idx_outbox_pending_claim
    ON opslab.outbox_events (created_at, claimed_at)
    WHERE status = 'PENDING';
