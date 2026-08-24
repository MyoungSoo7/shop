-- V20260715200000: 핵심 금액 컬럼을 NUMERIC(19,2) 로 통일 (금액 상한 정합)
--
-- 배경(DB 설계 리뷰 지적):
--   주문·결제·정산 등 거래 금액이 DECIMAL(10,2) = 최대 99,999,999.99 (~1억) 로 묶여 있었다.
--   그런데 원장(ledger_entries)은 14,2, 홀드백(holdback_amount)은 12,2 로 서로 상한이 달라
--   "정산 net_amount 는 1억까지, 원장 amount 는 1조까지" 같은 모순이 존재했다.
--   B2B·대량 주문은 주문 1건이 1억을 넘을 수 있어 실제 오버플로 위험이 있다.
--
-- 조치: order→payment→settlement→ledger→payout 로 이어지는 "거래 금액 체인" 전 컬럼을
--       NUMERIC(19,2)(최대 ~10^17) 으로 통일한다. PG 대사 금액·정산 프로젝션 뷰도 정합 확폭.
--       비율 컬럼(*_rate, NUMERIC(5,4))·재고 수량(INTEGER)·상품 카탈로그 정가(products.price 등)는
--       거래 금액 체인이 아니므로 이번 확폭 대상에서 제외한다.
--
-- 안전성:
--   - ddl-auto=validate: NUMERIC 은 가변길이 — scale 유지 + precision 확대는 Hibernate 검증에
--     영향 없다(컬럼 존재·타입 카테고리만 확인). 또한 settlements/ledger_entries/chargebacks/
--     payouts/*_view 는 order-service 엔티티가 매핑하지 않는다(정산 서비스 소유).
--   - PostgreSQL 12+: scale 불변 + precision 확대는 테이블 재작성(rewrite) 없이 메타데이터만 갱신.
--   - 기존 DEFAULT·CHECK·트리거(V30)·인덱스는 컬럼 타입 변경에 영향받지 않는다.

-- ── orders (order-service 매핑) ──
ALTER TABLE opslab.orders       ALTER COLUMN amount          TYPE NUMERIC(19, 2);
ALTER TABLE opslab.orders       ALTER COLUMN shipping_fee    TYPE NUMERIC(19, 2);

-- ── payments (order-service 매핑) ──
ALTER TABLE opslab.payments     ALTER COLUMN amount          TYPE NUMERIC(19, 2);
ALTER TABLE opslab.payments     ALTER COLUMN refunded_amount TYPE NUMERIC(19, 2);

-- ── refunds (order-service 매핑) ──
ALTER TABLE opslab.refunds      ALTER COLUMN amount          TYPE NUMERIC(19, 2);

-- ── order_items (order-service 매핑) ──
ALTER TABLE opslab.order_items  ALTER COLUMN unit_price      TYPE NUMERIC(19, 2);
ALTER TABLE opslab.order_items  ALTER COLUMN line_amount     TYPE NUMERIC(19, 2);

-- ── payment_tenders (order-service 매핑) ──
ALTER TABLE opslab.payment_tenders ALTER COLUMN amount          TYPE NUMERIC(19, 2);
ALTER TABLE opslab.payment_tenders ALTER COLUMN refunded_amount TYPE NUMERIC(19, 2);

-- ── settlements (정산 서비스 소유 — order 는 시드만 적재) ──
ALTER TABLE opslab.settlements  ALTER COLUMN payment_amount  TYPE NUMERIC(19, 2);
ALTER TABLE opslab.settlements  ALTER COLUMN commission      TYPE NUMERIC(19, 2);
ALTER TABLE opslab.settlements  ALTER COLUMN net_amount      TYPE NUMERIC(19, 2);
ALTER TABLE opslab.settlements  ALTER COLUMN refunded_amount TYPE NUMERIC(19, 2);
ALTER TABLE opslab.settlements  ALTER COLUMN holdback_amount TYPE NUMERIC(19, 2);
-- holdback_rate / commission_rate 는 비율(5,4) — 유지.

-- ── settlement_adjustments (금액 항상 음수, chk_adjustments_amount 유지) ──
ALTER TABLE opslab.settlement_adjustments ALTER COLUMN amount TYPE NUMERIC(19, 2);

-- ── chargebacks ──
ALTER TABLE opslab.chargebacks  ALTER COLUMN amount          TYPE NUMERIC(19, 2);

-- ── payouts ──
ALTER TABLE opslab.payouts      ALTER COLUMN amount          TYPE NUMERIC(19, 2);

-- ── ledger_entries (기존 14,2 → 19,2 로 상향 통일) ──
ALTER TABLE opslab.ledger_entries ALTER COLUMN amount        TYPE NUMERIC(19, 2);

-- ── ledger_outbox (REVERSE_ENTRY 환불 금액) ──
ALTER TABLE opslab.ledger_outbox ALTER COLUMN refund_amount  TYPE NUMERIC(19, 2);

-- ── PG 대사 금액 (내부/PG/차액 — difference 는 음수 가능) ──
ALTER TABLE opslab.pg_reconciliation_discrepancies ALTER COLUMN internal_amount TYPE NUMERIC(19, 2);
ALTER TABLE opslab.pg_reconciliation_discrepancies ALTER COLUMN pg_amount       TYPE NUMERIC(19, 2);
ALTER TABLE opslab.pg_reconciliation_discrepancies ALTER COLUMN difference      TYPE NUMERIC(19, 2);

-- ── 정산 프로젝션 뷰(ADR 0020, settlement 소유 read-model) — 15,2 → 19,2 정합 ──
ALTER TABLE opslab.settlement_payment_view ALTER COLUMN amount          TYPE NUMERIC(19, 2);
ALTER TABLE opslab.settlement_payment_view ALTER COLUMN refunded_amount TYPE NUMERIC(19, 2);
ALTER TABLE opslab.settlement_order_view   ALTER COLUMN amount          TYPE NUMERIC(19, 2);

-- settlement_loan_deductions.deducted 는 이미 NUMERIC(19,2) — 변경 없음(기준 정합 확인용 주석).
