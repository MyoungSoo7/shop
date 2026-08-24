-- V20260715130000: audit_logs 월별 RANGE 파티셔닝 전환 (확장성 축 보강)
--
-- [왜 파티셔닝인가]
--   audit_logs 는 append-only 감사 테이블이라 무한 증가한다. 단일 힙은 최근-구간 조회 지연과
--   대량 DELETE 리텐션의 블로트를 유발한다. 월별 파티션은 파티션 프루닝으로 최근 구간만 스캔하고,
--   리텐션을 DETACH+DROP(메타데이터 연산)으로 처리한다.
-- [왜 created_at 키인가]
--   감사 조회·리텐션이 모두 시간 축이라 created_at 이 유일 유효 키다. PK 를 (id, created_at) 복합으로
--   두어 파티션 키를 PK 에 포함하면서 id 전역 유일성·시퀀스 연속성을 유지한다. @GeneratedValue(IDENTITY)
--   는 기존 BIGSERIAL 시퀀스(audit_logs_id_seq)를 DEFAULT nextval 로 재사용 → INSERT ... RETURNING id
--   동작·id 연속성 보존(엔티티 매핑 무변경, ddl-auto=validate 통과).
-- [리텐션 정책]
--   금융 감사 로그는 장기 보관 원칙 — 기본 보존은 운영 정책에 위임하고 도구만 제공:
--   prune_audit_logs(retain_months)=DETACH+DROP(DEFAULT 보호), ensure_audit_log_partition(months_ahead)=선생성.
-- 기준 스키마: 기존 V3__audit_logs.sql (무접두, default_schema=opslab). 인덱스 3종은 이번에 표준으로 신규 추가.

-- 1) 기존 테이블·PK 리네임 (이름 충돌 회피)
ALTER TABLE audit_logs RENAME TO audit_logs_old;
ALTER TABLE audit_logs_old RENAME CONSTRAINT audit_logs_pkey TO audit_logs_old_pkey;

-- 2) 파티션드 부모 — 컬럼 구성 V3 와 동일, PK 만 (id, created_at). 기존 시퀀스 재사용으로 연속성 보존.
CREATE TABLE audit_logs (
    id              BIGINT       NOT NULL DEFAULT nextval('audit_logs_id_seq'),
    actor_id        BIGINT,
    actor_email     VARCHAR(255),
    action          VARCHAR(50)  NOT NULL,
    resource_type   VARCHAR(50),
    resource_id     VARCHAR(64),
    detail_json     JSONB,
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
ALTER SEQUENCE audit_logs_id_seq OWNED BY audit_logs.id;

-- 3) 월별 파티션 2026_01 ~ 2027_06 + DEFAULT
CREATE TABLE audit_logs_2026_01 PARTITION OF audit_logs FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE audit_logs_2026_02 PARTITION OF audit_logs FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE audit_logs_2026_03 PARTITION OF audit_logs FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE audit_logs_2026_04 PARTITION OF audit_logs FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE audit_logs_2026_05 PARTITION OF audit_logs FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE audit_logs_2026_06 PARTITION OF audit_logs FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE audit_logs_2026_07 PARTITION OF audit_logs FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE audit_logs_2026_08 PARTITION OF audit_logs FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE audit_logs_2026_09 PARTITION OF audit_logs FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE audit_logs_2026_10 PARTITION OF audit_logs FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE audit_logs_2026_11 PARTITION OF audit_logs FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE audit_logs_2026_12 PARTITION OF audit_logs FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE audit_logs_2027_01 PARTITION OF audit_logs FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE audit_logs_2027_02 PARTITION OF audit_logs FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE audit_logs_2027_03 PARTITION OF audit_logs FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE audit_logs_2027_04 PARTITION OF audit_logs FOR VALUES FROM ('2027-04-01') TO ('2027-05-01');
CREATE TABLE audit_logs_2027_05 PARTITION OF audit_logs FOR VALUES FROM ('2027-05-01') TO ('2027-06-01');
CREATE TABLE audit_logs_2027_06 PARTITION OF audit_logs FOR VALUES FROM ('2027-06-01') TO ('2027-07-01');
CREATE TABLE audit_logs_default  PARTITION OF audit_logs DEFAULT;

-- 4) 데이터 이관 후 구 테이블 제거
INSERT INTO audit_logs
    (id, actor_id, actor_email, action, resource_type, resource_id, detail_json, ip_address, user_agent, created_at)
SELECT id, actor_id, actor_email, action, resource_type, resource_id, detail_json, ip_address, user_agent, created_at
FROM audit_logs_old;
DROP TABLE audit_logs_old;

-- 5) 인덱스 표준 3종 신규 생성 (기존 V3 엔 없었음 — 전 서비스 표준화)
CREATE INDEX idx_audit_logs_actor_time  ON audit_logs (actor_id, created_at DESC);
CREATE INDEX idx_audit_logs_resource    ON audit_logs (resource_type, resource_id, created_at DESC);
CREATE INDEX idx_audit_logs_action_time ON audit_logs (action, created_at DESC);

-- 6) append-only 강제 (UPDATE·DELETE 거부) — 파티션드 부모 BEFORE ROW 트리거는 신규 파티션에도 자동 적용
CREATE OR REPLACE FUNCTION audit_logs_block_modify()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs 는 append-only 입니다: % 연산 불가 (감사 로그 변조 차단)', TG_OP;
END;
$$;
CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_block_modify();

-- 7) 유지보수 함수 (전 서비스 동일 시그니처)
CREATE OR REPLACE FUNCTION ensure_audit_log_partition(months_ahead int DEFAULT 1)
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
        part_name   := 'audit_logs_' || to_char(start_month, 'YYYY_MM');
        IF to_regclass(part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF audit_logs FOR VALUES FROM (%L) TO (%L)',
                part_name, start_month, end_month);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

CREATE OR REPLACE FUNCTION prune_audit_logs(retain_months int)
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
        WHERE p.relname = 'audit_logs'
          AND c.relname ~ '^audit_logs_[0-9]{4}_[0-9]{2}$'
    LOOP
        IF to_date(right(r.part_name, 7), 'YYYY_MM') < cutoff THEN
            EXECUTE format('ALTER TABLE audit_logs DETACH PARTITION %I', r.part_name);
            EXECUTE format('DROP TABLE %I', r.part_name);
            dropped := dropped + 1;
        END IF;
    END LOOP;
    RETURN dropped;
END;
$$;

COMMENT ON TABLE audit_logs IS '민감 작업 감사 추적. created_at 월별 RANGE 파티션 + append-only 트리거. 선생성=ensure_audit_log_partition, 리텐션=prune_audit_logs(DETACH+DROP, DEFAULT 보호).';
