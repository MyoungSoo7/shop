-- V20260715130200: ops_metric_bucket 월별 RANGE 파티셔닝 전환 (확장성 축 보강)
--
-- [왜 파티셔닝인가]
--   운영 신호 5분 버킷은 metric_key 마다 무한 누적되는 시계열이라 단일 힙이 급팽창한다. 이상탐지는
--   최근 구간(요일·시간대 베이스라인)만 스캔하므로 월별 파티션 프루닝이 조회를 크게 줄이고, 오래된
--   버킷 정리는 DETACH+DROP(메타데이터 연산)으로 처리한다.
-- [왜 bucket_start 키인가]
--   조회·리텐션이 모두 시간 축(bucket_start)이고, 기존 PK (metric_key, bucket_start) 가 이미 파티션 키를
--   포함하므로 UPSERT ON CONFLICT (metric_key, bucket_start) 가 파티션드 테이블에서 그대로 성립한다
--   (유니크에 파티션 키 포함 조건 충족). 컬럼 이름·타입·순서·NULL 은 V4 와 완전 동일.
-- [리텐션 정책]
--   운영 메트릭은 감사 로그보다 짧게 보관 — 기본값은 운영 정책에 위임하고 도구만 제공:
--   prune_ops_metric_bucket(retain_months)=DETACH+DROP(DEFAULT 보호), ensure_ops_metric_bucket_partition(months_ahead)=선생성.
-- 기준 스키마: 기존 V4__signal_metric_bucket.sql (무접두, default_schema=opslab). 네이티브 UPSERT 는 opslab.ops_metric_bucket 를 하드코딩(투명 호환).

-- 1) 기존 테이블·PK·인덱스 리네임 (이름 충돌 회피)
ALTER TABLE ops_metric_bucket RENAME TO ops_metric_bucket_old;
ALTER TABLE ops_metric_bucket_old RENAME CONSTRAINT ops_metric_bucket_pkey TO ops_metric_bucket_old_pkey;
ALTER INDEX idx_metric_bucket_recent RENAME TO idx_metric_bucket_recent_old;
ALTER INDEX idx_metric_bucket_time   RENAME TO idx_metric_bucket_time_old;

-- 2) 파티션드 부모 — 컬럼 구성 V4 와 동일, PK (metric_key, bucket_start) 유지(파티션 키 포함).
CREATE TABLE ops_metric_bucket (
    metric_key    VARCHAR(100)     NOT NULL,
    bucket_start  TIMESTAMPTZ      NOT NULL,
    count_total   BIGINT           NOT NULL DEFAULT 0,
    count_signal  BIGINT           NOT NULL DEFAULT 0,
    value_sum     DOUBLE PRECISION NOT NULL DEFAULT 0,
    value_max     DOUBLE PRECISION,
    sample_count  BIGINT           NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    PRIMARY KEY (metric_key, bucket_start)
) PARTITION BY RANGE (bucket_start);

-- 3) 월별 파티션 2026_01 ~ 2027_06 + DEFAULT. bucket_start 는 UTC 저장이라 경계도 +00 고정.
CREATE TABLE ops_metric_bucket_2026_01 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2026-02-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_02 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-02-01 00:00:00+00') TO ('2026-03-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_03 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-03-01 00:00:00+00') TO ('2026-04-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_04 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-04-01 00:00:00+00') TO ('2026-05-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_05 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_06 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_07 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_08 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_09 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_10 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_11 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2026_12 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2027_01 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2027_02 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2027-02-01 00:00:00+00') TO ('2027-03-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2027_03 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2027-03-01 00:00:00+00') TO ('2027-04-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2027_04 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2027-04-01 00:00:00+00') TO ('2027-05-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2027_05 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2027-05-01 00:00:00+00') TO ('2027-06-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_2027_06 PARTITION OF ops_metric_bucket FOR VALUES FROM ('2027-06-01 00:00:00+00') TO ('2027-07-01 00:00:00+00');
CREATE TABLE ops_metric_bucket_default  PARTITION OF ops_metric_bucket DEFAULT;

-- 4) 데이터 이관 후 구 테이블 제거
INSERT INTO ops_metric_bucket
    (metric_key, bucket_start, count_total, count_signal, value_sum, value_max, sample_count, updated_at)
SELECT metric_key, bucket_start, count_total, count_signal, value_sum, value_max, sample_count, updated_at
FROM ops_metric_bucket_old;
DROP TABLE ops_metric_bucket_old;

-- 5) 인덱스 동형 재생성
CREATE INDEX idx_metric_bucket_recent ON ops_metric_bucket (metric_key, bucket_start DESC);
CREATE INDEX idx_metric_bucket_time   ON ops_metric_bucket (bucket_start);

-- 6) 유지보수 함수 (append-only 트리거 없음 — 버킷은 UPSERT 로 갱신되는 가변 테이블)
CREATE OR REPLACE FUNCTION ensure_ops_metric_bucket_partition(months_ahead int DEFAULT 1)
RETURNS int
LANGUAGE plpgsql
SET search_path = opslab, pg_catalog
AS $$
DECLARE
    i int;
    start_month date;
    end_month date;
    part_name text;
    created int := 0;
BEGIN
    FOR i IN 0..months_ahead LOOP
        start_month := date_trunc('month', CURRENT_DATE + make_interval(months => i))::date;
        end_month   := (start_month + interval '1 month')::date;
        part_name   := 'ops_metric_bucket_' || to_char(start_month, 'YYYY_MM');
        IF to_regclass(part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF ops_metric_bucket FOR VALUES FROM (%L) TO (%L)',
                part_name, start_month::timestamptz, end_month::timestamptz);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

CREATE OR REPLACE FUNCTION prune_ops_metric_bucket(retain_months int)
RETURNS int
LANGUAGE plpgsql
SET search_path = opslab, pg_catalog
AS $$
DECLARE
    cutoff date;
    r record;
    dropped int := 0;
BEGIN
    IF retain_months < 1 THEN
        RAISE EXCEPTION 'retain_months 는 1 이상이어야 합니다 (요청: %)', retain_months;
    END IF;
    cutoff := (date_trunc('month', CURRENT_DATE) - make_interval(months => retain_months))::date;
    FOR r IN
        SELECT c.relname AS part_name
        FROM pg_inherits inh
        JOIN pg_class c ON c.oid = inh.inhrelid
        JOIN pg_class p ON p.oid = inh.inhparent
        WHERE p.relname = 'ops_metric_bucket'
          AND c.relname ~ '^ops_metric_bucket_[0-9]{4}_[0-9]{2}$'
    LOOP
        IF to_date(right(r.part_name, 7), 'YYYY_MM') < cutoff THEN
            EXECUTE format('ALTER TABLE ops_metric_bucket DETACH PARTITION %I', r.part_name);
            EXECUTE format('DROP TABLE %I', r.part_name);
            dropped := dropped + 1;
        END IF;
    END LOOP;
    RETURN dropped;
END;
$$;

COMMENT ON TABLE ops_metric_bucket IS '운영 신호 5분 버킷. bucket_start 월별 RANGE 파티션. UPSERT ON CONFLICT(metric_key,bucket_start) 투명 호환. 선생성=ensure_ops_metric_bucket_partition, 리텐션=prune_ops_metric_bucket(DETACH+DROP, DEFAULT 보호).';
