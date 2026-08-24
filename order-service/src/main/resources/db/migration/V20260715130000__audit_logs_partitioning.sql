-- V20260715130000: audit_logs 월별 RANGE 파티셔닝 전환 (확장성 축 보강)
--
-- [왜 파티셔닝인가]
--   audit_logs 는 민감 작업(정산 확정·환불·권한 변경·로그인 실패 등)마다 무한 append 되는
--   append-only 테이블이라 시간이 지나면 단일 힙이 수천만~수억 행으로 비대해진다. 단일 테이블은
--   (1) autovacuum·인덱스 비대화로 최근-구간 조회가 느려지고 (2) 리텐션 삭제가 대량 DELETE →
--   블로트·롱 트랜잭션을 유발한다. 월별 파티션은 최근 구간만 스캔(파티션 프루닝)하고, 리텐션은
--   DETACH+DROP(메타데이터 연산)으로 O(1) 처리한다.
-- [왜 created_at 키인가]
--   모든 감사 조회는 시간 축(actor/resource/action × created_at DESC)이고 리텐션도 시간 기준이라
--   created_at 이 프루닝·삭제 양쪽에 유효한 유일 키다. PK 를 (id, created_at) 복합으로 두어
--   파티션 키를 PK 에 포함(파티션드 테이블 제약)하면서 id 의 전역 유일성·시퀀스 연속성은 유지한다.
--   @GeneratedValue(IDENTITY) 는 기존 BIGSERIAL 시퀀스를 그대로 재사용(DEFAULT nextval)하므로
--   INSERT ... RETURNING id 동작과 id 연속성이 모두 보존된다(엔티티 매핑 무변경, ddl-auto=validate 통과).
-- [리텐션 정책]
--   금융 감사 로그는 장기 보관이 원칙 — 기본 보존은 운영 정책에 위임하고, 도구만 제공한다:
--   prune_audit_logs(retain_months) 로 명시 호출 시에만 오래된 파티션을 DETACH+DROP(DEFAULT 파티션 보호).
--   ensure_audit_log_partition(months_ahead) 로 미래 파티션을 선생성(스케줄러/크론에서 주기 호출).

-- 1) 기존 테이블·의존 객체 리네임 (신규 파티션드 테이블과 이름 충돌 회피)
ALTER TABLE opslab.audit_logs RENAME TO audit_logs_old;
ALTER TABLE opslab.audit_logs_old RENAME CONSTRAINT audit_logs_pkey TO audit_logs_old_pkey;
ALTER INDEX opslab.idx_audit_logs_actor_time  RENAME TO idx_audit_logs_actor_time_old;
ALTER INDEX opslab.idx_audit_logs_resource    RENAME TO idx_audit_logs_resource_old;
ALTER INDEX opslab.idx_audit_logs_action_time RENAME TO idx_audit_logs_action_time_old;

-- 2) 파티션드 부모 — 컬럼 구성은 V34 와 동일(이름·타입·순서·NULL), PK 만 (id, created_at) 복합.
--    id 는 기존 시퀀스(opslab.audit_logs_id_seq)를 DEFAULT nextval 로 재사용해 연속성 보존.
CREATE TABLE opslab.audit_logs (
    id              BIGINT       NOT NULL DEFAULT nextval('opslab.audit_logs_id_seq'),
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
ALTER SEQUENCE opslab.audit_logs_id_seq OWNED BY opslab.audit_logs.id;

-- 3) 월별 파티션 2026_01 ~ 2027_06 선생성 + DEFAULT 파티션(범위 밖 삽입 실패 방지)
CREATE TABLE opslab.audit_logs_2026_01 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE opslab.audit_logs_2026_02 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE opslab.audit_logs_2026_03 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE opslab.audit_logs_2026_04 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE opslab.audit_logs_2026_05 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE opslab.audit_logs_2026_06 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE opslab.audit_logs_2026_07 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE opslab.audit_logs_2026_08 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE opslab.audit_logs_2026_09 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE opslab.audit_logs_2026_10 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE opslab.audit_logs_2026_11 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE opslab.audit_logs_2026_12 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE opslab.audit_logs_2027_01 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE opslab.audit_logs_2027_02 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE opslab.audit_logs_2027_03 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE opslab.audit_logs_2027_04 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-04-01') TO ('2027-05-01');
CREATE TABLE opslab.audit_logs_2027_05 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-05-01') TO ('2027-06-01');
CREATE TABLE opslab.audit_logs_2027_06 PARTITION OF opslab.audit_logs FOR VALUES FROM ('2027-06-01') TO ('2027-07-01');
CREATE TABLE opslab.audit_logs_default  PARTITION OF opslab.audit_logs DEFAULT;

-- 4) 데이터 이관 후 구 테이블 제거 (구 PK·인덱스·구 default 는 함께 소멸, 시퀀스는 소유권 이전으로 생존)
INSERT INTO opslab.audit_logs
    (id, actor_id, actor_email, action, resource_type, resource_id, detail_json, ip_address, user_agent, created_at)
SELECT id, actor_id, actor_email, action, resource_type, resource_id, detail_json, ip_address, user_agent, created_at
FROM opslab.audit_logs_old;
DROP TABLE opslab.audit_logs_old;

-- 5) 인덱스 동형 재생성 (V34 의 3종 — 파티션드 부모에 걸면 전 파티션에 자동 전파)
CREATE INDEX idx_audit_logs_actor_time  ON opslab.audit_logs (actor_id, created_at DESC);
CREATE INDEX idx_audit_logs_resource    ON opslab.audit_logs (resource_type, resource_id, created_at DESC);
CREATE INDEX idx_audit_logs_action_time ON opslab.audit_logs (action, created_at DESC);

-- 6) append-only 강제 — 감사 로그 변조 차단(UPDATE·DELETE 를 DB 레벨에서 거부).
--    파티션드 부모에 건 BEFORE ROW 트리거는 이후 생성되는 파티션에도 자동 적용된다.
CREATE OR REPLACE FUNCTION opslab.audit_logs_block_modify()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs 는 append-only 입니다: % 연산 불가 (감사 로그 변조 차단)', TG_OP;
END;
$$;
CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON opslab.audit_logs
    FOR EACH ROW EXECUTE FUNCTION opslab.audit_logs_block_modify();

-- 7) 유지보수 함수 (전 서비스 동일 시그니처)
--    ensure_audit_log_partition(months_ahead): 이번 달 ~ N개월 뒤 파티션을 없으면 선생성.
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

--    prune_audit_logs(retain_months): retain_months 이전 월별 파티션을 DETACH 후 DROP.
--    DEFAULT 파티션(audit_logs_default)은 정규식에서 제외되어 절대 삭제되지 않는다.
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

COMMENT ON TABLE opslab.audit_logs IS '민감 작업 감사 추적. created_at 월별 RANGE 파티션 + append-only 트리거. 파티션 선생성=ensure_audit_log_partition, 리텐션=prune_audit_logs(DETACH+DROP, DEFAULT 보호).';
