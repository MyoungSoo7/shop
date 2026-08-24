-- V20260716300300: audit_logs 파티션 런웨이 확대 (2027_07 ~ 2028_12) — 크로스컷 DB 리뷰 F3 (order-service (opslab 스키마))
--
-- [설계 근거]
--   audit_logs 는 2027_06 까지만 파티션이 선생성돼 있어(월별 RANGE 파티션 도입 마이그레이션), 그 이후 삽입은
--   DEFAULT 파티션으로 흘러든다. DEFAULT 에 데이터가 쌓이면 프루닝 이점이 사라지고 리텐션(DETACH+DROP)도
--   무력화되므로, 월별 파티션을 2028_12 까지 미리 깐다.
-- [⚠ DEFAULT 파티션 트랩]
--   특정 월 데이터가 이미 DEFAULT 파티션에 들어간 뒤에는 그 월의 파티션을 CREATE ... PARTITION OF 로 만들 때
--   "default partition 에 해당 범위 행 존재"로 실패한다. 이 확대는 해당 구간에 데이터가 쌓이기 전에 적용돼야
--   하며(현 시점 2026-07 기준 전부 미래 구간이라 안전), 대용량 운영 환경에서의 최초 전환은 반드시 점검창
--   (maintenance window)에서 수행한다.
-- [운영 주의 — 근본 해법]
--   런웨이 소진을 막는 정답은 ensure_audit_log_partition(months_ahead) 를 월 1회 크론/스케줄러로 호출해 미래 파티션을 굴리는
--   것이다. 이 마이그레이션은 그 스케줄러가 붙기 전까지의 일회성 런웨이(약 18개월) 확보다.

CREATE TABLE IF NOT EXISTS opslab.audit_logs_2027_07 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-07-01') TO ('2027-08-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2027_08 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-08-01') TO ('2027-09-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2027_09 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-09-01') TO ('2027-10-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2027_10 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-10-01') TO ('2027-11-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2027_11 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-11-01') TO ('2027-12-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2027_12 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-12-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_01 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-01-01') TO ('2028-02-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_02 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-02-01') TO ('2028-03-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_03 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-03-01') TO ('2028-04-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_04 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-04-01') TO ('2028-05-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_05 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-05-01') TO ('2028-06-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_06 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-06-01') TO ('2028-07-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_07 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-07-01') TO ('2028-08-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_08 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-08-01') TO ('2028-09-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_09 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-09-01') TO ('2028-10-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_10 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-10-01') TO ('2028-11-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_11 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-11-01') TO ('2028-12-01');
CREATE TABLE IF NOT EXISTS opslab.audit_logs_2028_12 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2028-12-01') TO ('2029-01-01');
