-- V20260718110000: R5 리뷰 후속 폴리시 팩 (order/opslab)
--
-- [① 중복 인덱스] V1 idx_users_email 은 users.email UNIQUE 제약의 자동 유니크 인덱스와 완전 중복
--   (V20260715200004 정리에서 누락) → DROP.
-- [② opslab.payouts PII 비대칭 근거] settlement_db.payouts 는 enc:v1 앱단 암호화(V20260716200200)인데
--   opslab.payouts 는 평문 스키마 — 비대칭으로 보이나: opslab 측은 레거시·대사(recon) 전용이며
--   **시드 INSERT 가 전무해 실 PII 데이터가 존재하지 않는다**(V17/V21/V50 전수 확인). 정산 정본과
--   지급 실데이터는 settlement_db 소유(ADR 0020, V20260716300100 소유권 COMMENT). 이 사실을 컬럼에 각인.
-- [③ 다형참조 트레이드오프] ledger_entries.reference_id 는 reference_type(SETTLEMENT|REFUND) 분기
--   다형참조라 FK 불가 — 중복분개 방지는 uq_ledger_reference_accounts, 참조 정합은 대사가 담당함을 각인.
-- [④ BRIN 튜닝] idx_ledger_entries_created_brin 을 pages_per_range=32, autosummarize=on 으로 재생성 —
--   기본(128)보다 좁은 범위 요약으로 최근-구간 조회 정밀도를 높이고, 신규 블록 요약을 VACUUM 대기 없이
--   자동화한다(append-only 시간순 적재라 요약 비용 미미).
-- [⑤ DEFAULT 파티션 프로브] default_partition_rows() — DEFAULT 파티션에 유입된 행 수를 반환하는 운영
--   프로브. 0 이 정상이며 >0 이면 런웨이 소진 신호(ensure_* 미발화) — 모니터링이 이 함수를 폴링해
--   알람한다(ADR 0027 결과 절의 감시 요구 구현).

-- ① 중복 인덱스 정리
DROP INDEX IF EXISTS opslab.idx_users_email;

-- ② PII 비대칭 근거 각인
COMMENT ON COLUMN opslab.payouts.bank_account_number IS
    '레거시·대사 전용 스키마(실데이터 없음 — 시드 INSERT 전무, 지급 정본은 settlement_db.payouts 의 enc:v1 암호화 컬럼). 이 테이블에 실 계좌를 적재하려면 먼저 동일 암호화를 배선할 것.';

-- ③ 다형참조 트레이드오프 각인
COMMENT ON COLUMN opslab.ledger_entries.reference_id IS
    'reference_type(SETTLEMENT|REFUND) 분기 다형참조 — FK 불가(의도). 중복분개 방지는 uq_ledger_reference_accounts, 참조 정합은 일일 대사가 담당.';

-- ④ BRIN 튜닝 재생성
DROP INDEX IF EXISTS opslab.idx_ledger_entries_created_brin;
CREATE INDEX idx_ledger_entries_created_brin
    ON opslab.ledger_entries USING BRIN (created_at)
    WITH (pages_per_range = 32, autosummarize = on);

-- ⑤ DEFAULT 파티션 프로브
CREATE OR REPLACE FUNCTION default_partition_rows()
RETURNS TABLE(parent_table text, default_rows bigint)
LANGUAGE plpgsql
SET search_path = opslab, pg_catalog
AS $$
DECLARE
    r record;
    cnt bigint;
BEGIN
    FOR r IN
        SELECT p.relname AS parent, c.relname AS child
        FROM pg_inherits inh
        JOIN pg_class c ON c.oid = inh.inhrelid
        JOIN pg_class p ON p.oid = inh.inhparent
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'opslab'
          AND c.relname LIKE '%\_default' ESCAPE '\'
    LOOP
        EXECUTE format('SELECT count(*) FROM %I', r.child) INTO cnt;
        parent_table := r.parent;
        default_rows := cnt;
        RETURN NEXT;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION default_partition_rows() IS
    'DEFAULT 파티션 유입 행 수 프로브 — 0 정상, >0 은 런웨이 소진 신호(ensure_* 미발화). 모니터링 폴링 대상(ADR 0027).';
