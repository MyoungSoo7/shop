-- V20260820140000: 배송비 정책 — 상품 배송비 유형 + 셀러 기본배송비/무료배송 임계
--
-- [문제]
--   orders.shipping_fee 컬럼(V20260701100000)은 있었지만 그 값을 채우는 규칙이 어디에도 없었다.
--   운영 코드에서 Order.assignShippingFee 를 호출하는 곳이 0 개라 실질적으로 모든 주문의 배송비가
--   0 이었고, 배송 후 환불에서 배송비를 차감하는 RefundPolicy 도 항상 0 을 차감했다 — 정책은
--   있는데 입력이 없어 죽어 있는 분기였다.
--
-- [조치] 실무 커머스(SSG B2E)의 2 축 구조를 옮긴다.
--   ① 상품 축: shipping_charge_type
--        FREE        — 무조건 무료
--        SELLER_BASE — 셀러 기본배송비 대상(셀러당 1 회, 무료배송 임계 이상이면 면제)
--        INDIVIDUAL  — 상품 개별배송비(무료 조건과 무관하게 라인마다 부과)
--   ② 셀러 축: seller_shipping_policies(base_fee, free_threshold)
--
--   free_threshold 는 NULL 이면 "무료배송 조건 없음"(항상 부과)이고 0 이면 "항상 무료"다 —
--   둘은 다른 의미라 NOT NULL DEFAULT 0 으로 뭉개지 않는다.
--
-- [기존 데이터] 기존 상품은 전부 FREE 로 시작한다(DEFAULT). 배송비는 결제 금액에 더해지므로
--   과거 주문·정산 금액에는 영향이 없고, 시드 셀러 3인에만 정책을 심어 신규 주문이 실제로
--   조건부 무료배송 분기를 타게 한다.

-- ── ① 상품 축 ────────────────────────────────────────────────────────────────
ALTER TABLE opslab.products
    ADD COLUMN IF NOT EXISTS shipping_charge_type VARCHAR(20)   NOT NULL DEFAULT 'FREE',
    ADD COLUMN IF NOT EXISTS shipping_charge_fee  NUMERIC(19,2) NOT NULL DEFAULT 0;

ALTER TABLE opslab.products
    DROP CONSTRAINT IF EXISTS ck_products_shipping_charge_type;
ALTER TABLE opslab.products
    ADD CONSTRAINT ck_products_shipping_charge_type
        CHECK (shipping_charge_type IN ('FREE', 'SELLER_BASE', 'INDIVIDUAL'));

-- 개별배송 상품인데 금액이 0 이면 조용히 무료배송이 된다 — 부과 누락을 스키마에서 막는다
-- (도메인 ShippingLine 의 같은 불변식과 2 중 방어).
ALTER TABLE opslab.products
    DROP CONSTRAINT IF EXISTS ck_products_individual_fee_positive;
ALTER TABLE opslab.products
    ADD CONSTRAINT ck_products_individual_fee_positive
        CHECK (shipping_charge_type <> 'INDIVIDUAL' OR shipping_charge_fee > 0);

ALTER TABLE opslab.products
    DROP CONSTRAINT IF EXISTS ck_products_shipping_charge_fee_nonneg;
ALTER TABLE opslab.products
    ADD CONSTRAINT ck_products_shipping_charge_fee_nonneg
        CHECK (shipping_charge_fee >= 0);

COMMENT ON COLUMN opslab.products.shipping_charge_type IS
    '배송비 부과 유형 — FREE(무료) / SELLER_BASE(셀러 기본배송비, 조건부 무료) / INDIVIDUAL(상품 개별배송비)';
COMMENT ON COLUMN opslab.products.shipping_charge_fee IS
    'INDIVIDUAL 일 때 라인당 부과액(그 외 유형에서는 사용하지 않음)';

-- ── ② 셀러 축 ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS opslab.seller_shipping_policies (
    seller_id      BIGINT        PRIMARY KEY,
    base_fee       NUMERIC(19,2) NOT NULL DEFAULT 0,
    free_threshold NUMERIC(19,2),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_seller_shipping_policies_seller
        FOREIGN KEY (seller_id) REFERENCES opslab.users(id),
    CONSTRAINT ck_seller_shipping_policies_base_fee    CHECK (base_fee >= 0),
    CONSTRAINT ck_seller_shipping_policies_threshold   CHECK (free_threshold IS NULL OR free_threshold >= 0)
);

COMMENT ON TABLE opslab.seller_shipping_policies IS
    '셀러 배송비 정책 — 기본배송비와 무료배송 임계(NULL = 무료배송 조건 없음)';
COMMENT ON COLUMN opslab.seller_shipping_policies.free_threshold IS
    '이 금액 이상이면 기본배송비 면제(경계는 이상 포함). NULL 이면 조건 없음, 0 이면 항상 무료';

-- ── ③ 시드 — 등급별 셀러 3 인에 서로 다른 정책 ───────────────────────────────
-- 등급이 높을수록 무료배송 문턱이 낮다: 정책 차이가 데모·수동확인에서 눈에 보이도록.
INSERT INTO opslab.seller_shipping_policies (seller_id, base_fee, free_threshold)
SELECT u.id, v.base_fee, v.free_threshold
  FROM (VALUES
            ('seed_manager@test.com',          3000::NUMERIC(19,2), 50000::NUMERIC(19,2)),
            ('seed_seller_vip@test.com',       2500::NUMERIC(19,2), 30000::NUMERIC(19,2)),
            ('seed_seller_strategic@test.com', 2500::NUMERIC(19,2), 20000::NUMERIC(19,2))
       ) AS v(email, base_fee, free_threshold)
  JOIN opslab.users u ON u.email = v.email
ON CONFLICT (seller_id) DO NOTHING;

-- 시드 상품을 셀러 기본배송비 대상으로 돌린다 — 정책 행만 있고 이를 참조하는 상품이 없으면
-- 조건부 무료배송 분기는 한 번도 실행되지 않는다(정책이 있는 것과 경로가 도는 것은 다른 문제).
UPDATE opslab.products
   SET shipping_charge_type = 'SELLER_BASE'
 WHERE shipping_charge_type = 'FREE'
   AND seller_id IN (SELECT seller_id FROM opslab.seller_shipping_policies);
