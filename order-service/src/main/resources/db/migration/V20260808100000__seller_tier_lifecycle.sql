-- 셀러 등급 라이프사이클 (ADR 0031).
--
-- users.seller_tier 는 지금까지 수기 UPDATE 로만 바뀌었고 변경 이력도 산정 기준도 없었다.
-- 그 값 하나가 수수료율·정산주기·홀드백 3축을 동시에 결정하는데(SellerTier enum), 돈에 직결되는
-- 값이 흔적 없이 바뀌는 상태였다. 정본 테이블과 append-only 이력을 둔다.

-- 현재 등급 (정본)
CREATE TABLE IF NOT EXISTS opslab.seller_tier_assignment (
    seller_id              BIGINT      PRIMARY KEY,
    tier                   VARCHAR(20) NOT NULL,
    effective_from         DATE        NOT NULL,
    -- 강등 유예 만료일. 이 날짜까지는 조건을 못 채워도 등급을 유지한다(등급 진동 억제).
    demotion_guard_until   DATE,
    -- 연속 미달 횟수. 유예가 끝나도 이 값이 기준에 도달해야 강등된다.
    consecutive_miss_count SMALLINT    NOT NULL DEFAULT 0,
    last_evaluated_at      TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_sta_tier CHECK (tier IN ('NORMAL', 'VIP', 'STRATEGIC')),
    CONSTRAINT chk_sta_miss CHECK (consecutive_miss_count >= 0)
);

COMMENT ON TABLE opslab.seller_tier_assignment IS
    '셀러 등급 정본 (ADR 0031) — users.seller_tier 는 이 값을 따라가는 읽기 캐시';

-- 변경 이력 (append-only — UPDATE/DELETE 금지)
CREATE TABLE IF NOT EXISTS opslab.seller_tier_history (
    id                 BIGSERIAL   PRIMARY KEY,
    seller_id          BIGINT      NOT NULL,
    prev_tier          VARCHAR(20),                -- 최초 부여 시 NULL
    new_tier           VARCHAR(20) NOT NULL,
    reason             VARCHAR(32) NOT NULL,       -- AUTO_PROMOTION | AUTO_DEMOTION | ADMIN_OVERRIDE
    -- 판정 근거 거래액. 나중에 "왜 이때 올랐나"를 설명하려면 값이 남아야 한다.
    basis_amount       NUMERIC(18,2),
    basis_period_start DATE,
    basis_period_end   DATE,
    changed_by         VARCHAR(64) NOT NULL,       -- 'SYSTEM' 또는 관리자 식별자
    memo               VARCHAR(255),
    changed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_sth_new_tier CHECK (new_tier IN ('NORMAL', 'VIP', 'STRATEGIC')),
    CONSTRAINT chk_sth_reason CHECK (reason IN ('AUTO_PROMOTION', 'AUTO_DEMOTION', 'ADMIN_OVERRIDE'))
);

CREATE INDEX IF NOT EXISTS idx_sth_seller
    ON opslab.seller_tier_history (seller_id, changed_at DESC);

COMMENT ON TABLE opslab.seller_tier_history IS
    '셀러 등급 변경 이력 (ADR 0031) — append-only. 수정은 새 행으로만';

-- 기존 users.seller_tier 값을 정본으로 옮긴다. 이 시점의 등급이 그대로 유지되므로 이행으로 인해
-- 셀러의 수수료·정산주기·홀드백이 바뀌지 않는다(무행동 착지).
INSERT INTO opslab.seller_tier_assignment (seller_id, tier, effective_from)
SELECT u.id, COALESCE(u.seller_tier, 'NORMAL'), CURRENT_DATE
  FROM opslab.users u
 WHERE EXISTS (SELECT 1 FROM opslab.products p WHERE p.seller_id = u.id)
ON CONFLICT (seller_id) DO NOTHING;
