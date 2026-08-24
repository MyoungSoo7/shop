-- V20260820170000: 주문당 포인트 사용 상한 정책
--
-- [문제]
--   포인트는 잔액만 있으면 결제 전액을 덮을 수 있었다. 실무 커머스는 대개 상한을 둔다 —
--   정액("주문당 최대 1 만 포인트")이거나 비율("결제액의 30% 까지"). 상한을 코드에 두면 판촉
--   기간마다 배포해야 하고, 그 사이 실제 사용은 정책과 어긋난다.
--
-- [조치] 단일 행 정책 테이블. 적립률(point_earn_policy)처럼 기간을 갖는 이력형이 아니다 —
--   사용 상한은 "지금 이 순간의 규칙"이고, 과거 주문에 소급 재계산되지 않는다(사용액은 이미
--   원장에 확정돼 있다). 그래서 이력 대신 현재값 한 행 + 변경 주체 기록으로 충분하다.
--
--   id = 1 CHECK 로 행이 하나뿐임을 스키마가 강제한다. 두 행이 생기면 어느 쪽이 규칙인지
--   설명할 수 없고, 애플리케이션의 "첫 행" 관례는 정렬 우연에 기대게 된다.
--
-- [기본값] NONE(상한 없음) — 이 마이그레이션만으로 기존 동작이 달라지지 않는다.

CREATE TABLE IF NOT EXISTS point_usage_limit_policy (
    id                  SMALLINT      PRIMARY KEY,
    limit_type          VARCHAR(20)   NOT NULL DEFAULT 'NONE',
    limit_amount        NUMERIC(19,2),
    limit_ratio_percent NUMERIC(5,2),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_by          VARCHAR(64),

    CONSTRAINT ck_pulp_singleton CHECK (id = 1),
    CONSTRAINT ck_pulp_type      CHECK (limit_type IN ('NONE', 'FIXED_AMOUNT', 'ORDER_RATIO')),
    -- 유형이 요구하는 값이 비어 있으면 상한이 조용히 사라진다 — 스키마에서 막는다.
    CONSTRAINT ck_pulp_fixed     CHECK (limit_type <> 'FIXED_AMOUNT'
                                        OR (limit_amount IS NOT NULL AND limit_amount >= 0)),
    CONSTRAINT ck_pulp_ratio     CHECK (limit_type <> 'ORDER_RATIO'
                                        OR (limit_ratio_percent IS NOT NULL
                                            AND limit_ratio_percent BETWEEN 0 AND 100))
);

COMMENT ON TABLE point_usage_limit_policy IS
    '주문당 포인트 사용 상한(현재값 1행) — NONE 상한없음 / FIXED_AMOUNT 정액 / ORDER_RATIO 결제액 비율';
COMMENT ON COLUMN point_usage_limit_policy.limit_amount IS
    'FIXED_AMOUNT 의 상한. 0 은 "포인트 사용 금지"이며 NONE(상한 없음)과 다른 의미다';

INSERT INTO point_usage_limit_policy (id, limit_type, updated_by)
VALUES (1, 'NONE', 'migration')
ON CONFLICT (id) DO NOTHING;
