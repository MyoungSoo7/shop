-- V45: Ledger (원장) 도메인.
--
-- 모든 금전 거래를 복식부기(double-entry bookkeeping) 방식으로 기록한다.
-- 한 비즈니스 거래(예: 정산 1건)는 동일 reference_id·reference_type 로 묶이는
-- 여러 LedgerEntry row 로 표현되며, 각 row 는 (debit_account, credit_account, amount)
-- 한 쌍의 분개를 의미한다. amount 는 항상 양수이고 차·대 부호는 account 로 결정한다.
--
-- Settlement / Refund 도메인과의 관계:
--   - Settlement DONE 시 SETTLEMENT_CONFIRMED 분개 row(들) 생성.
--   - 환불/분쟁 발생 시 REFUND_REVERSED 분개 row(들) 생성. 원 entry 는 REVERSED 마킹.
--   - LedgerEntry 자체는 불변(Immutable). 정정이 필요하면 reverse 후 신규 작성.
--
-- 설계 문서: docs/ledger-domain-design.md

CREATE TABLE IF NOT EXISTS opslab.ledger_entries (
    id               BIGSERIAL PRIMARY KEY,
    reference_id     BIGINT        NOT NULL,           -- settlement.id 또는 refund.id
    reference_type   VARCHAR(30)   NOT NULL,           -- SETTLEMENT | REFUND
    entry_type       VARCHAR(50)   NOT NULL,           -- LedgerEntryType enum
    debit_account    VARCHAR(50)   NOT NULL,           -- AccountType enum
    credit_account   VARCHAR(50)   NOT NULL,           -- AccountType enum
    amount           DECIMAL(14, 2) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    settlement_date  DATE          NOT NULL,
    posted_at        TIMESTAMP,
    memo             VARCHAR(500),
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_ledger_amount     CHECK (amount > 0),
    CONSTRAINT chk_ledger_accounts   CHECK (debit_account <> credit_account),
    CONSTRAINT chk_ledger_status     CHECK (status IN ('PENDING', 'POSTED', 'REVERSED')),
    CONSTRAINT chk_ledger_ref_type   CHECK (reference_type IN ('SETTLEMENT', 'REFUND'))
);

CREATE INDEX IF NOT EXISTS idx_ledger_reference        ON opslab.ledger_entries (reference_id, reference_type);
CREATE INDEX IF NOT EXISTS idx_ledger_entry_type       ON opslab.ledger_entries (entry_type);
CREATE INDEX IF NOT EXISTS idx_ledger_status           ON opslab.ledger_entries (status);
CREATE INDEX IF NOT EXISTS idx_ledger_settlement_date  ON opslab.ledger_entries (settlement_date);
CREATE INDEX IF NOT EXISTS idx_ledger_debit_account    ON opslab.ledger_entries (debit_account);
CREATE INDEX IF NOT EXISTS idx_ledger_credit_account   ON opslab.ledger_entries (credit_account);

COMMENT ON TABLE  opslab.ledger_entries IS '원장 항목. 한 거래는 reference_id 로 묶이는 여러 row 로 표현. 불변 원칙.';
COMMENT ON COLUMN opslab.ledger_entries.reference_id   IS '연결된 settlement.id 또는 refund.id';
COMMENT ON COLUMN opslab.ledger_entries.reference_type IS 'SETTLEMENT | REFUND';
COMMENT ON COLUMN opslab.ledger_entries.entry_type     IS 'SETTLEMENT_CREATED, SETTLEMENT_CONFIRMED, REFUND_REVERSED, COMMISSION_RECOGNIZED, PAYOUT_EXECUTED';
COMMENT ON COLUMN opslab.ledger_entries.amount         IS '거래 금액. 항상 양수. 차변·대변 부호는 account 로 결정.';
COMMENT ON COLUMN opslab.ledger_entries.status         IS 'PENDING(작성) → POSTED(전기) → REVERSED(역분개)';
