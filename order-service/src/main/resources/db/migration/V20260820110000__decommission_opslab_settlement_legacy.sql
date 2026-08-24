-- V20260820100000: opslab 정산계 레거시 테이블 디커미션 (ADR 0020 Phase 5.5 마무리)
--
-- [왜 지금, 왜 마이그레이션인가]
--   정산·정산금·차지백·원장의 정본은 settlement_db 다(ADR 0020 CQRS 분리). opslab 에 남은 동명
--   테이블은 모놀리스 잔재이고, order 코드의 실제 접근은 0건이다(참조는 전부 주석·메뉴 경로 문자열).
--
--   그동안 제거는 scripts/etl/settlement-opslab-decommission.sh 의 수동 실행에 맡겨져 있었다. 그
--   스크립트는 말미에 "신규 opslab 부트스트랩 시 빈 테이블이 재생성되나 무해"라고 적었는데, 그
--   가정이 틀렸다 — V17__seed_data.sql 이 opslab.settlements 에 정산 1,000행을 채우므로 재생성되는
--   것은 빈 테이블이 아니라 좀비 데이터다. 운영 DB 에서 한 번 지워도 새 환경을 띄우면 부활한다.
--   실측(OpslabSettlementDecommissionMigrationIT, fresh Postgres 에 order Flyway 전량 적용):
--     settlements=1000행 · settlement_schedule_config=3행 · 나머지 13개=0행.
--   수동 스크립트로는 이 부활 고리를 끊을 수 없어 마이그레이션으로 승격한다.
--
-- [안전성 근거]
--   1) recon 내부 API(/internal/recon)가 읽는 opslab 테이블은 payments·refunds·outbox_events 뿐이다
--      (ReconQueryRepository 의 FROM 절 전수 확인). 과거 V20260716300100 은 "recon 이 읽으므로 삭제
--      금지"라 적었으나 현재 코드와 맞지 않는다 — 그 COMMENT 는 대상 테이블과 함께 사라진다.
--   2) order JPA 엔티티(@Table) 어디에도 아래 테이블이 없다 → Hibernate validate 영향 0.
--   3) CASCADE 는 드롭 대상에 의존하는 객체만 정리한다. 정산계→order 방향 FK(settlements.order_id 등)가
--      있어도 부모인 orders/payments 는 데이터까지 무사하다(OpslabDecommissionIT 가 격리 DB 에서 입증).
--   4) 순서는 자식(FK 참조) → 부모. IF EXISTS 이므로 이미 스크립트로 제거한 운영 DB 에서도 무해하다.
--
-- [보존] outbox_events · processed_events · audit_logs · shedlock · batch_run_history 는 order 가
--        계속 쓰는 공유 테이블이라 대상이 아니다. orders/payments 의 V17 시드 행도 그대로 남는다.
--
-- ⚠ 비가역. 이 마이그레이션 이후 opslab 에서 정산 이력을 조회할 수 없다(정본은 settlement_db).

DROP TABLE IF EXISTS opslab.settlement_adjustments CASCADE;
DROP TABLE IF EXISTS opslab.settlement_loan_deductions CASCADE;
DROP TABLE IF EXISTS opslab.pg_reconciliation_discrepancies CASCADE;
DROP TABLE IF EXISTS opslab.pg_reconciliation_runs CASCADE;
DROP TABLE IF EXISTS opslab.ledger_outbox CASCADE;
DROP TABLE IF EXISTS opslab.ledger_entries CASCADE;
DROP TABLE IF EXISTS opslab.chargebacks CASCADE;
DROP TABLE IF EXISTS opslab.payouts CASCADE;
DROP TABLE IF EXISTS opslab.settlement_index_queue CASCADE;
DROP TABLE IF EXISTS opslab.settlement_schedule_config CASCADE;
DROP TABLE IF EXISTS opslab.settlement_payment_view CASCADE;
DROP TABLE IF EXISTS opslab.settlement_order_view CASCADE;
DROP TABLE IF EXISTS opslab.settlement_user_view CASCADE;
DROP TABLE IF EXISTS opslab.settlement_product_view CASCADE;
DROP TABLE IF EXISTS opslab.settlements CASCADE;

-- 트리거는 테이블과 함께 사라지지만 함수는 별개 객체라 고아로 남는다. 둘 다 정산계 전용이므로
-- (settlements / ledger_entries 에만 부착) 여기서 같이 걷어낸다.
DROP FUNCTION IF EXISTS opslab.enforce_settlement_immutability();
DROP FUNCTION IF EXISTS opslab.enforce_ledger_immutability();
