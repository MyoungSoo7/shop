-- V20260716300100: opslab 정산계 레거시 테이블 소유권 명문화 (ADR 0020) — 크로스컷 DB 리뷰 F3 (order)
--
-- [설계 근거]
--   정산·정산금·차지백·원장의 정본(source of truth)은 settlement-service 의 settlement_db 다(ADR 0020 에서
--   order↔settlement 를 이벤트 드리븐 CQRS 로 분리). opslab(order DB)에 남아 있는 동명 테이블들은 분리 이전
--   모놀리스 잔재로, 지금은 대사(/internal/recon)·시드·레거시 조회 경로에서만 읽힌다.
--   COMMENT 만 부여해 "정본 아님"을 DB 메타데이터에 명시(운영자·후임자 오인 방지).
-- [⚠ 구조 변경 금지]
--   recon 내부 API 가 이 테이블/컬럼을 읽으므로 rename·컬럼 변경·삭제는 대사를 깨뜨린다. 본 마이그레이션은
--   COMMENT 만 남기고 구조는 일절 건드리지 않는다.

COMMENT ON TABLE opslab.settlements IS
    '[레거시/대사용] 정산 정본은 settlement_db(ADR 0020 CQRS 분리). opslab 측은 대사·시드·레거시 경로 전용 — 구조 변경·rename 금지(recon 내부 API 가 읽음).';
COMMENT ON TABLE opslab.payouts IS
    '[레거시/대사용] 정산금 지급 정본은 settlement_db. opslab 측은 대사·시드·레거시 경로 전용 — 구조 변경·rename 금지.';
COMMENT ON TABLE opslab.chargebacks IS
    '[레거시/대사용] 차지백 정본은 settlement_db. opslab 측은 대사·시드·레거시 경로 전용 — 구조 변경·rename 금지.';
COMMENT ON TABLE opslab.ledger_entries IS
    '[레거시/대사용] 원장(GL) 정본은 settlement_db. opslab 측은 대사·시드·레거시 경로 전용 — 구조 변경·rename 금지.';
COMMENT ON TABLE opslab.settlement_adjustments IS
    '[레거시/대사용] 정산 조정(역정산) 정본은 settlement_db. opslab 측은 대사·시드·레거시 경로 전용 — 구조 변경·rename 금지.';
COMMENT ON TABLE opslab.ledger_outbox IS
    '[레거시/대사용] 원장 이벤트 outbox 레거시 잔재. 정산·원장 이벤트 정본 흐름은 settlement_db 측 — 구조 변경·rename 금지.';
