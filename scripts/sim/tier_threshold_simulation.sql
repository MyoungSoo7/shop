-- 셀러 등급 임계 시뮬레이션 (ADR 0031 결정 ①)
--
-- "VIP·STRATEGIC 임계를 얼마로 두면 몇 명이 승급하고 수수료 수입이 얼마나 줄어드는가"를 산출한다.
-- 등급 승급은 곧 수수료 인하라, 임계값 결정은 포기할 수입의 크기를 아는 상태에서만 할 수 있다.
--
-- 실행:
--   psql -U <user> -d <order_db> -v vip=500000000 -v strategic=3000000000 \
--        -f scripts/sim/tier_threshold_simulation.sql
--
-- 기준: ADR 0031 결정 ①(a) — order 자기 DB 의 결제 순액(CAPTURED − 환불), 셀러별 12개월.
--       settlement 확정 순액과 미세 차이가 날 수 있으나 등급은 구간 판정이라 근사로 충분하다.
--
-- 주의: 이 스크립트는 읽기 전용이다. 등급을 바꾸지 않는다.

\set ON_ERROR_STOP on
\if :{?vip}
\else
  \set vip 500000000
\endif
\if :{?strategic}
\else
  \set strategic 3000000000
\endif

-- ── 셀러별 12개월 결제 순액 ────────────────────────────────────────────────
WITH seller_net AS (
    SELECT pr.seller_id,
           COALESCE(u.seller_tier, 'NORMAL')                        AS current_tier,
           SUM(p.amount - COALESCE(p.refunded_amount, 0))           AS net_12m
      FROM opslab.payments p
      JOIN opslab.orders   o  ON o.id  = p.order_id
      JOIN opslab.products pr ON pr.id = o.product_id
      LEFT JOIN opslab.users u ON u.id = pr.seller_id
     WHERE p.status = 'CAPTURED'
       AND p.captured_at >= now() - interval '12 months'
       AND pr.seller_id IS NOT NULL
     GROUP BY pr.seller_id, u.seller_tier
),
-- ── 임계 적용 후 등급 ──────────────────────────────────────────────────────
projected AS (
    SELECT seller_id, current_tier, net_12m,
           CASE WHEN net_12m >= :strategic THEN 'STRATEGIC'
                WHEN net_12m >= :vip       THEN 'VIP'
                ELSE 'NORMAL' END AS projected_tier
      FROM seller_net
),
-- ── 등급별 수수료율 (SellerTier 와 동기화 — 바뀌면 여기도 고칠 것) ─────────
rate AS (
    SELECT * FROM (VALUES
        ('NORMAL',    0.0350::numeric, 0.30::numeric, 30),
        ('VIP',       0.0250::numeric, 0.10::numeric, 14),
        ('STRATEGIC', 0.0200::numeric, 0.00::numeric,  0)
    ) AS t(tier, commission_rate, holdback_rate, holdback_days)
),
priced AS (
    SELECT p.*,
           rc.commission_rate AS cur_rate,  rp.commission_rate AS new_rate,
           rc.holdback_rate   AS cur_hb,    rp.holdback_rate   AS new_hb,
           rc.holdback_days   AS cur_hb_d,  rp.holdback_days   AS new_hb_d
      FROM projected p
      JOIN rate rc ON rc.tier = p.current_tier
      JOIN rate rp ON rp.tier = p.projected_tier
)
-- ── ① 등급 이동 요약 ──────────────────────────────────────────────────────
SELECT '① 등급 이동'                                        AS section,
       current_tier || ' → ' || projected_tier              AS movement,
       count(*)                                             AS sellers,
       to_char(SUM(net_12m), 'FM999,999,999,999')           AS net_12m,
       to_char(SUM(net_12m * cur_rate), 'FM999,999,999,999') AS commission_now,
       to_char(SUM(net_12m * new_rate), 'FM999,999,999,999') AS commission_after,
       to_char(SUM(net_12m * (cur_rate - new_rate)), 'FM999,999,999,999') AS revenue_given_up
  FROM priced
 GROUP BY current_tier, projected_tier
 ORDER BY 3 DESC;

-- ── ② 총계: 포기하는 수수료와 줄어드는 홀드백 완충 ────────────────────────
WITH seller_net AS (
    SELECT pr.seller_id,
           COALESCE(u.seller_tier, 'NORMAL') AS current_tier,
           SUM(p.amount - COALESCE(p.refunded_amount, 0)) AS net_12m
      FROM opslab.payments p
      JOIN opslab.orders   o  ON o.id  = p.order_id
      JOIN opslab.products pr ON pr.id = o.product_id
      LEFT JOIN opslab.users u ON u.id = pr.seller_id
     WHERE p.status = 'CAPTURED'
       AND p.captured_at >= now() - interval '12 months'
       AND pr.seller_id IS NOT NULL
     GROUP BY pr.seller_id, u.seller_tier
),
projected AS (
    SELECT seller_id, current_tier, net_12m,
           CASE WHEN net_12m >= :strategic THEN 'STRATEGIC'
                WHEN net_12m >= :vip       THEN 'VIP'
                ELSE 'NORMAL' END AS projected_tier
      FROM seller_net
),
rate AS (
    SELECT * FROM (VALUES
        ('NORMAL',    0.0350::numeric, 0.30::numeric, 30),
        ('VIP',       0.0250::numeric, 0.10::numeric, 14),
        ('STRATEGIC', 0.0200::numeric, 0.00::numeric,  0)
    ) AS t(tier, commission_rate, holdback_rate, holdback_days)
),
priced AS (
    SELECT p.*, rc.commission_rate cur_rate, rp.commission_rate new_rate,
           rc.holdback_rate cur_hb, rp.holdback_rate new_hb,
           rc.holdback_days cur_hb_d, rp.holdback_days new_hb_d
      FROM projected p
      JOIN rate rc ON rc.tier = p.current_tier
      JOIN rate rp ON rp.tier = p.projected_tier
)
SELECT '② 총계' AS section,
       count(*) FILTER (WHERE current_tier <> projected_tier) AS sellers_moving,
       to_char(SUM(net_12m * (cur_rate - new_rate)), 'FM999,999,999,999') AS annual_revenue_given_up,
       -- 정상 상태 홀드백 잔액 ≈ 순액 × 보류율 × 보류일수 / 365 (완충 규모)
       to_char(SUM(net_12m * cur_hb * cur_hb_d / 365), 'FM999,999,999,999') AS holdback_buffer_now,
       to_char(SUM(net_12m * new_hb * new_hb_d / 365), 'FM999,999,999,999') AS holdback_buffer_after
  FROM priced;
