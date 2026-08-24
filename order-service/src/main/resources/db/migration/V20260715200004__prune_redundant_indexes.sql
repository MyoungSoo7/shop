-- V20260715200004: 중복·저효율 인덱스 정리 + 원장 계정 인덱스 실조회 패턴화
--
-- 배경(DB 설계 리뷰 지적):
--   ① V2 의 단일컬럼 인덱스 여러 개가 V3/V22 의 상위 복합/유니크 인덱스에 완전히 포함되어
--      중복이다(선두 컬럼이 동일 → 옵티마이저가 복합 인덱스로 단일컬럼 조회를 커버).
--   ② ledger_entries 의 debit_account/credit_account 단일컬럼 인덱스는 저선택도(계정 종류 소수)라
--      실효가 낮고, 실제 리포지토리 조회는 계정 단독이 아니라 "기간(settlement_date) + 계정" 이다.
--
-- 판단 기준: "정확히 동일 정의 또는 상위 복합 인덱스에 완전히 포함되는 것"만 DROP한다.
--   (선두 컬럼이 다른 인덱스는 단일컬럼 조회를 커버하지 못하므로 보존.)

-- ── ① 상위 인덱스에 흡수된 중복 단일컬럼 인덱스 제거 ──

-- settlements(payment_id): 비유니크 idx_settlements_payment_id 는
-- 동일 컬럼의 UNIQUE idx_settlements_payment_id_unique(V3) 가 완전 대체 → DROP.
DROP INDEX IF EXISTS opslab.idx_settlements_payment_id;

-- orders(user_id): 복합 idx_orders_user_id_status(V22, 선두 user_id) 가 커버 → DROP.
DROP INDEX IF EXISTS opslab.idx_orders_user_id;

-- payments(status): 복합 idx_payments_status_updated_at(V3, 선두 status) 가 커버 → DROP.
DROP INDEX IF EXISTS opslab.idx_payments_status;

-- settlements(settlement_date): 복합 idx_settlements_date_status(V3/V22, 선두 settlement_date) 가 커버 → DROP.
DROP INDEX IF EXISTS opslab.idx_settlements_settlement_date;

-- (보존: idx_orders_status·idx_orders_created_at·idx_payments_updated_at·idx_settlements_order_id·
--  idx_settlements_status 는 이를 선두로 하는 복합 인덱스가 없어 대체 불가 → 유지.)

-- ── ② ledger_entries 계정 인덱스: 저선택도 단일컬럼 → 기간+계정 복합으로 대체 ──
--
-- 실조회 패턴(SpringDataLedgerJpaRepository): findBySettlementDateBetween(기간 범위) +
-- 시산표는 그 기간을 계정별로 집계(GROUP BY account). 따라서 (settlement_date, account) 복합이
-- "기간 스캔 후 계정 집계" 를 모두 커버하며, 계정 단독 저선택도 인덱스보다 실효가 크다.
DROP INDEX IF EXISTS opslab.idx_ledger_debit_account;
DROP INDEX IF EXISTS opslab.idx_ledger_credit_account;

CREATE INDEX IF NOT EXISTS idx_ledger_date_debit
    ON opslab.ledger_entries (settlement_date, debit_account);
CREATE INDEX IF NOT EXISTS idx_ledger_date_credit
    ON opslab.ledger_entries (settlement_date, credit_account);
