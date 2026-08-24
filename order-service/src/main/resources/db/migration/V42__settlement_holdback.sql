-- V42: 정산 보류(Holdback) — 셀러 신뢰도 기반 일정 비율을 N일간 보류
--
-- 신규/저신뢰 셀러는 환불 다발 위험이 있어 정산금 일부를 보류했다가 분쟁 없으면 풀어준다.
-- 보류 정책 (셀러 등급 기반 default):
--   NORMAL    : 30%, 30 일 보류
--   VIP       : 10%, 14 일 보류
--   STRATEGIC :  0% (즉시 전액 정산)
--
-- 환불 발생 시 holdback 에서 우선 차감 (이미 보류된 금액 사용) — 셀러 추가 부담 없음.
-- 보류 해제는 별도 배치 (HoldbackReleaseScheduler) 가 release_date 도달한 row 처리.

ALTER TABLE opslab.settlements
    ADD COLUMN IF NOT EXISTS holdback_amount       NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS holdback_rate         NUMERIC(5, 4)  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS holdback_release_date DATE,
    ADD COLUMN IF NOT EXISTS holdback_released     BOOLEAN        NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS holdback_released_at  TIMESTAMP;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_name = 'chk_settlements_holdback_amount'
           AND table_schema = 'opslab'
           AND table_name = 'settlements'
    ) THEN
        ALTER TABLE opslab.settlements
            ADD CONSTRAINT chk_settlements_holdback_amount
                CHECK (holdback_amount >= 0);
    END IF;
END $$;

-- 보류 해제 배치 조회용 인덱스
CREATE INDEX IF NOT EXISTS idx_settlements_holdback_release
    ON opslab.settlements (holdback_release_date)
    WHERE holdback_amount > 0 AND holdback_released = FALSE;

COMMENT ON COLUMN opslab.settlements.holdback_amount IS
    '정산금 중 일정 기간 보류된 금액. 즉시 지급액 = net_amount - holdback_amount.';
COMMENT ON COLUMN opslab.settlements.holdback_rate IS
    '정산 시점 적용된 보류율 (4자리 소수). 이력 보존 — 정책 변경 후에도 과거 정산 추적 가능.';
COMMENT ON COLUMN opslab.settlements.holdback_release_date IS
    '보류 해제 예정일. 이 날짜 이후 HoldbackReleaseScheduler 배치가 풀어준다.';
COMMENT ON COLUMN opslab.settlements.holdback_released IS
    'TRUE = 보류 해제됨 (셀러 출금 가능). 환불로 보류금 전액 소진된 경우도 TRUE.';
