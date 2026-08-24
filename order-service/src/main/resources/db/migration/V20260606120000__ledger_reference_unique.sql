-- 원장(ledger_entries) 중복 분개 방지 UNIQUE 제약.
--
-- 배경: CreateLedgerEntryService 는 existsByReference 소프트 체크로 멱등을 보장하지만,
-- 동시에 같은 settlementId 확정이 두 번 실행되면 양쪽 모두 existsByReference=false 를 보고
-- 각각 분개를 INSERT 해 동일 분개가 2배로 적재될 수 있다(TOCTOU 경합). 복식부기 대차가 깨진다.
--
-- 한 정산은 (reference_id, reference_type) 가 같은 서로 다른 계정쌍 row 들로 분해되므로
-- (예: ACCOUNTS_PAYABLE/REVENUE 행 + COMMISSION_EXPENSE/COMMISSION_REVENUE 행),
-- reference 만으로는 UNIQUE 할 수 없다. 계정쌍까지 포함해 "정확한 중복 분개"만 차단한다.
--
-- 효과: 동시 중복 실행 시 두 번째 INSERT 가 DB 레벨에서 거부 → 트랜잭션 롤백 →
-- 아웃박스 폴러/배치가 재시도 → existsByReference 가 true 가 되어 skip. 이중 적재 0.

CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_reference_accounts
    ON opslab.ledger_entries (reference_id, reference_type, debit_account, credit_account);

COMMENT ON INDEX opslab.uq_ledger_reference_accounts IS
    '중복 분개 방지 — 같은 (거래, 계정쌍) 조합의 원장 항목은 단 1건만 허용. 동시 정산 확정 이중 적재 차단.';
