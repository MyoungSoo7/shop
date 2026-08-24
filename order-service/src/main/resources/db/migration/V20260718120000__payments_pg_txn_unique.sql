-- V20260718120000: payments.pg_transaction_id 부분 유니크 — PG 웹훅 이중발화 멱등 방어 (R5 리뷰 후속)
--
-- [지적 · A-med] settlements 는 uk_settlements_payment_id 로 멱등이 보장되나, 결제 자체는 동일
--   PG 거래키의 중복 캡처(웹훅 재전송·이중발화)를 DB 레벨에서 막지 못했다. 결제→정산→원장으로
--   전파되기 전의 최상류 멱등 방어선을 유니크로 세운다.
-- [부분 유니크 사유] pg_transaction_id 는 PG 미경유 텐더(포인트/상품권 전액)·캡처 이전(READY) 결제에서
--   NULL 이 정상 — WHERE IS NOT NULL 부분 유니크로 NULL 다중 허용.
-- [시드 안전성] V17('seed_txn_'||i)·V21('jan2026_txn_'||...) 은 접두가 달라 전역 충돌 없음(전수 확인).
--   유니크 인덱스는 NOT VALID 불가 — 생성 시 전수 검사되며, 기존 DB 에 중복이 있으면 생성 실패로
--   노출된다(그 자체가 이중 캡처 결함 신호 — 의도된 fail-loud).
-- [경계] payment_tenders.pg_transaction_id 는 분할결제 텐더별 독립 거래키라 테이블 내 유니크만 의미
--   있고, discrepancies 는 PG 측 원본 보존 컬럼이라 중복(재대사)이 정상 — 이 둘은 제외.

CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_pg_txn
    ON opslab.payments (pg_transaction_id)
    WHERE pg_transaction_id IS NOT NULL;

COMMENT ON INDEX opslab.uq_payments_pg_txn IS
    'PG 거래키 멱등 방어선 — 웹훅 이중발화로 인한 중복 결제행 차단(NULL=PG 미경유/캡처 전, 다중 허용).';
