-- V20260715200001: 원장(ledger_entries) POSTED 불변성 DB 강제 트리거
--
-- 배경(DB 설계 리뷰 지적):
--   V45 는 status 전이(PENDING→POSTED→REVERSED)를 CHECK 로 값만 제한할 뿐,
--   "POSTED 전표의 금액·계정·참조는 절대 바꿀 수 없고 삭제할 수 없다"는 복식부기 핵심 불변식이
--   DB 레벨에서 강제되지 않았다. 애플리케이션 버그·수기 UPDATE 가 전기된 원장을 훼손하면
--   차·대 균형과 시산표가 조용히 무너진다. V30(settlements 불변 트리거)과 동일한 방식으로 방어한다.
--
-- 강제 규칙:
--   - 전기(POSTED)된 전표의 금액·차변·대변·참조·거래유형·정산일 변경 차단(불변).
--   - 허용 전이는 POSTED → REVERSED 단 하나(역분개 연결). 그 외 상태 변경(예: POSTED→PENDING) 차단.
--   - REVERSED 는 종료 상태 — 어떤 UPDATE 도 차단.
--   - 전기(POSTED)·역분개(REVERSED)된 전표의 DELETE 차단(원장 이력 영구 보존). PENDING 은 롤백 가능.
--   - 정정은 반드시 "신규 역분개 전표 작성 + 원 전표 REVERSED 마킹" 으로만.

-- 1) 역분개 연결 컬럼: 원 전표에서 자신을 역분개한 전표를 가리킨다(POSTED→REVERSED 시 세팅 허용).
ALTER TABLE opslab.ledger_entries
    ADD COLUMN IF NOT EXISTS reversal_entry_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_name = 'fk_ledger_reversal_entry'
           AND table_schema = 'opslab' AND table_name = 'ledger_entries'
    ) THEN
        ALTER TABLE opslab.ledger_entries
            ADD CONSTRAINT fk_ledger_reversal_entry
            FOREIGN KEY (reversal_entry_id) REFERENCES opslab.ledger_entries(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_ledger_reversal_entry
    ON opslab.ledger_entries (reversal_entry_id)
    WHERE reversal_entry_id IS NOT NULL;

COMMENT ON COLUMN opslab.ledger_entries.reversal_entry_id IS
    '이 전표를 역분개한 전표의 id. POSTED→REVERSED 전이 시에만 세팅. 원장 정정은 역분개로만.';

-- 2) 불변성 강제 함수 (UPDATE·DELETE 공용)
CREATE OR REPLACE FUNCTION opslab.enforce_ledger_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('POSTED', 'REVERSED') THEN
            RAISE EXCEPTION
                'Ledger entry id=% is % (immutable). Posted/reversed entries cannot be deleted; reverse via a new entry.',
                OLD.id, OLD.status
                USING ERRCODE = '23514';  -- check_violation
        END IF;
        RETURN OLD;
    END IF;

    -- TG_OP = 'UPDATE'
    IF OLD.status = 'POSTED' THEN
        -- 금액·계정·참조·거래유형·정산일은 전기 후 불변.
        IF (NEW.amount, NEW.debit_account, NEW.credit_account, NEW.reference_id,
            NEW.reference_type, NEW.entry_type, NEW.settlement_date)
           IS DISTINCT FROM
           (OLD.amount, OLD.debit_account, OLD.credit_account, OLD.reference_id,
            OLD.reference_type, OLD.entry_type, OLD.settlement_date) THEN
            RAISE EXCEPTION
                'Ledger entry id=% is POSTED (immutable). Financial/reference columns cannot be modified; reverse via a new entry.',
                OLD.id
                USING ERRCODE = '23514';
        END IF;
        -- 허용 전이는 POSTED→REVERSED 뿐. POSTED 유지(무변경 재저장)는 허용, 그 외 상태는 차단.
        IF NEW.status NOT IN ('POSTED', 'REVERSED') THEN
            RAISE EXCEPTION
                'Ledger entry id=% POSTED can only transition to REVERSED (attempted status=%).',
                OLD.id, NEW.status
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.status = 'REVERSED' THEN
        RAISE EXCEPTION
            'Ledger entry id=% is REVERSED (terminal, immutable). No further updates allowed.',
            OLD.id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ledger_immutability_upd ON opslab.ledger_entries;
CREATE TRIGGER trg_ledger_immutability_upd
    BEFORE UPDATE ON opslab.ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION opslab.enforce_ledger_immutability();

DROP TRIGGER IF EXISTS trg_ledger_immutability_del ON opslab.ledger_entries;
CREATE TRIGGER trg_ledger_immutability_del
    BEFORE DELETE ON opslab.ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION opslab.enforce_ledger_immutability();

COMMENT ON FUNCTION opslab.enforce_ledger_immutability() IS
    'POSTED 원장 전표의 금액·계정·참조 변경 및 POSTED/REVERSED 전표 삭제 차단. 허용 전이는 POSTED→REVERSED 뿐.';
