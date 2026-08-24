-- V20260718100000: R4 리뷰 후속 팩 — 폭 드리프트 해소·중복 인덱스 정리·저선택도 인덱스 교체·감사 PII 계약 (order)
--
-- [① pg_transaction_id 폭 정합] 리뷰 지적: 동일 개념의 폭 불일치 — payments(100, V16) vs
--   payment_tenders(500)·pg_reconciliation_discrepancies(500). 게다가 PaymentJpaEntity 는 이미
--   @Column(length=500) 로 선언되어 있어 엔티티↔DB 드리프트 상태였다. DB 를 500 으로 확폭해
--   상한을 단일화한다(VARCHAR 확폭은 rewrite 없는 메타데이터 변경).
-- [② 중복 인덱스] V45 idx_ledger_reference(reference_id, reference_type)는
--   V20260606120000 uq_ledger_reference_accounts(reference_id, reference_type, ...) 의 선두 2컬럼에
--   완전 포섭된다(유니크 인덱스가 접두 조회를 커버) → DROP. V20260715200004 정리 때 누락된 항목.
-- [③ 저선택도 인덱스 교체] idx_settlements_status(status 단일)는 DONE 편중 저선택도. 실조회는
--   status×settlement_date 축이므로 (status, settlement_date) 복합으로 교체 — status-only 조건도
--   선두 컬럼으로 커버되어 회귀 없음.
-- [④ 감사 detail_json PII 계약] audit_logs.detail_json 은 변경 전/후 값을 담는 JSONB 라 PII 유입
--   가능 — 마스킹은 기록기(공통 audit 적재 경로)의 단일 초크포인트 책임임을 스키마에 계약으로 각인.
--   (payouts 처럼 컬럼 암호화하지 않는 이유: 감사 로그는 조회·검색이 1급 요구라 검색 가능성 보존,
--   대신 유입 전 마스킹 강제 + append-only 로 사후 변조 차단.)

-- ① 폭 정합
ALTER TABLE opslab.payments
    ALTER COLUMN pg_transaction_id TYPE VARCHAR(500);
COMMENT ON COLUMN opslab.payments.pg_transaction_id IS
    'PG 거래키. 폭 상한 500 으로 전 테이블(payment_tenders/discrepancies) 단일화 — 엔티티 length=500 정합.';

-- ② 중복 인덱스 정리
DROP INDEX IF EXISTS opslab.idx_ledger_reference;

-- ③ 저선택도 단일 인덱스 → 복합 교체
CREATE INDEX IF NOT EXISTS idx_settlements_status_date
    ON opslab.settlements (status, settlement_date);
DROP INDEX IF EXISTS opslab.idx_settlements_status;

-- ④ 감사 PII 마스킹 계약
COMMENT ON COLUMN opslab.audit_logs.detail_json IS
    '작업 상세(변경 전/후 값). PII 는 기록기 단일 초크포인트에서 마스킹 후 유입되어야 한다(평문 계좌·주민번호 금지). append-only 라 사후 정정 불가 — 유입 전 마스킹이 계약.';
