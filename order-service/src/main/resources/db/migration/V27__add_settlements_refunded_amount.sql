-- V27: settlements.refunded_amount 컬럼 추가
-- SettlementJpaEntity 는 refundedAmount 필드를 갖고 있으나
-- 기존 마이그레이션에 해당 컬럼 생성 DDL 이 빠져 있어 Hibernate schema validation 실패.
-- 이를 복구한다. (default 0 으로 기존 행 채움, NOT NULL 제약 유지)

ALTER TABLE opslab.settlements
    ADD COLUMN IF NOT EXISTS refunded_amount NUMERIC(10, 2) NOT NULL DEFAULT 0;
