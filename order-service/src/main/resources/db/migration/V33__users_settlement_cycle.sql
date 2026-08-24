-- V33: 판매자별 정산 주기 (SettlementCycle)
--
-- T3-⑦(b). 판매자가 정산을 일/주/월 단위로 선택할 수 있도록 users.settlement_cycle 컬럼 추가.
-- 기본 DAILY 로 백필 — 기존 정책과 호환.

ALTER TABLE opslab.users
    ADD COLUMN IF NOT EXISTS settlement_cycle VARCHAR(20) NOT NULL DEFAULT 'DAILY';

ALTER TABLE opslab.users
    ADD CONSTRAINT chk_users_settlement_cycle
        CHECK (settlement_cycle IN ('DAILY', 'WEEKLY_MON', 'MONTHLY_LAST'));

COMMENT ON COLUMN opslab.users.settlement_cycle IS '판매자 정산 주기 — DAILY(기본), WEEKLY_MON(매주 월요일), MONTHLY_LAST(매월 말일)';
