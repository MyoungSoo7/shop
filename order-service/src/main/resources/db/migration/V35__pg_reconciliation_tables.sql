-- V35: PG 정산파일 대사(reconciliation) 테이블
--
-- 목적: PG 사가 매일 보내주는 일일 정산 파일과 내부 결제·환불 원장을 자동 비교한다.
-- 차액이 발견되면 차액 종류별 (반올림 / 누락 / 이중청구 / 금액 불일치) 분류해
-- 자동 보정 가능한 항목(반올림 1원 미만)은 즉시 SettlementAdjustment 로 흘리고,
-- 그 외는 운영자 검토 큐에 적재한다.
--
-- 이커머스 / 결제 회사 운영 사이클의 핵심 자동화 — 매월 1~2건 발생하는 차액을
-- 사람이 엑셀로 비교하던 작업을 시스템화.

-- 1) 대사 실행 1회 메타데이터
CREATE TABLE IF NOT EXISTS opslab.pg_reconciliation_runs (
    id                    BIGSERIAL PRIMARY KEY,
    pg_provider           VARCHAR(20)  NOT NULL,                 -- TOSS / KCP / NICE / INICIS
    target_date           DATE         NOT NULL,                 -- 어느 영업일에 대한 대사인지
    file_name             VARCHAR(255) NOT NULL,                 -- 업로드된 PG 파일명 (감사 추적)
    status                VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    started_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    finished_at           TIMESTAMP,
    total_pg_rows         INTEGER      NOT NULL DEFAULT 0,
    total_internal_rows   INTEGER      NOT NULL DEFAULT 0,
    matched_count         INTEGER      NOT NULL DEFAULT 0,
    discrepancy_count     INTEGER      NOT NULL DEFAULT 0,
    auto_corrected_count  INTEGER      NOT NULL DEFAULT 0,
    operator_id           VARCHAR(100),                          -- 업로드한 운영자
    note                  TEXT,
    CONSTRAINT chk_pg_reconciliation_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_pg_reconciliation_runs_target_date
    ON opslab.pg_reconciliation_runs (target_date DESC);

CREATE INDEX IF NOT EXISTS idx_pg_reconciliation_runs_provider_date
    ON opslab.pg_reconciliation_runs (pg_provider, target_date DESC);

COMMENT ON TABLE opslab.pg_reconciliation_runs IS 'PG 정산파일 1회 대사 실행 메타. 동일 PG/날짜 재실행은 별도 row 로 누적.';
COMMENT ON COLUMN opslab.pg_reconciliation_runs.discrepancy_count IS 'MATCHED 가 아닌 모든 차이 합계 — 0 이면 완전 일치';

-- 2) 발견된 개별 불일치(discrepancy)
CREATE TABLE IF NOT EXISTS opslab.pg_reconciliation_discrepancies (
    id                    BIGSERIAL PRIMARY KEY,
    run_id                BIGINT       NOT NULL,
    type                  VARCHAR(30)  NOT NULL,                 -- AMOUNT_MISMATCH / MISSING_INTERNAL / MISSING_PG / DUPLICATE / ROUNDING_DIFF
    payment_id            BIGINT,                                -- 내부 payments.id (없으면 NULL)
    pg_transaction_id     VARCHAR(500),                          -- PG 파일에 기록된 거래 키
    internal_amount       NUMERIC(12, 2),                        -- 내부 원장 금액
    pg_amount             NUMERIC(12, 2),                        -- PG 파일 금액
    difference            NUMERIC(12, 2),                        -- pg_amount - internal_amount
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    resolved_at           TIMESTAMP,
    resolved_by           VARCHAR(100),
    note                  TEXT,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_pg_recon_discrepancy_run
        FOREIGN KEY (run_id) REFERENCES opslab.pg_reconciliation_runs(id) ON DELETE CASCADE,
    CONSTRAINT chk_pg_recon_discrepancy_type
        CHECK (type IN ('AMOUNT_MISMATCH', 'MISSING_INTERNAL', 'MISSING_PG', 'DUPLICATE', 'ROUNDING_DIFF')),
    CONSTRAINT chk_pg_recon_discrepancy_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'AUTO_CORRECTED'))
);

CREATE INDEX IF NOT EXISTS idx_pg_recon_discrepancy_run
    ON opslab.pg_reconciliation_discrepancies (run_id, status);

CREATE INDEX IF NOT EXISTS idx_pg_recon_discrepancy_payment
    ON opslab.pg_reconciliation_discrepancies (payment_id)
    WHERE payment_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pg_recon_discrepancy_status
    ON opslab.pg_reconciliation_discrepancies (status, type)
    WHERE status = 'PENDING';

COMMENT ON COLUMN opslab.pg_reconciliation_discrepancies.type IS
    'AMOUNT_MISMATCH=금액 차이 큼(>1원) / MISSING_INTERNAL=PG 파일에만 존재(거래 누락 의심) / '
    'MISSING_PG=내부에만 존재(미정산 또는 PG 누락) / DUPLICATE=PG 파일 중복 / ROUNDING_DIFF=1원 미만 자동 보정';
COMMENT ON COLUMN opslab.pg_reconciliation_discrepancies.status IS
    'PENDING=운영자 검토 대기 / APPROVED=역정산 승인 / REJECTED=무시 / AUTO_CORRECTED=ROUNDING_DIFF 자동 처리';
