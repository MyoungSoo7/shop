-- V31: products 테이블에 판매자(seller_id) 연결.
--
-- 목적: 판매자별 cashflow 리포트(T3-⑨(c)) 의 선행 조건으로 상품-판매자 연결을 정의한다.
-- T3-⑦ SellerTier (차등 수수료) 는 별도 마이그레이션에서 확장 예정.
--
-- 호환성: NULLABLE 로 추가하여 기존 상품 레코드 파괴 없음. FK 도 ON DELETE SET NULL 로
-- 판매자 계정이 제거되어도 상품은 유지된다.

ALTER TABLE opslab.products
    ADD COLUMN IF NOT EXISTS seller_id BIGINT;

ALTER TABLE opslab.products
    ADD CONSTRAINT fk_products_seller
        FOREIGN KEY (seller_id) REFERENCES opslab.users(id)
        ON DELETE SET NULL;

-- 판매자별 조회 최적화 (판매자별 리포트 WHERE 조건)
CREATE INDEX IF NOT EXISTS idx_products_seller_id
    ON opslab.products(seller_id)
    WHERE seller_id IS NOT NULL;

-- 데모 시드: 기존 시드 상품을 seed_manager 에 귀속시켜 판매자별 리포트가
-- localhost 에서 즉시 값을 반환하도록 한다. 운영 환경에서는 판매자 등록 플로우에서 채워진다.
UPDATE opslab.products
SET seller_id = (SELECT id FROM opslab.users WHERE email = 'seed_manager@test.com')
WHERE seller_id IS NULL;

COMMENT ON COLUMN opslab.products.seller_id IS '판매자(users.id). NULL 허용 — 레거시/미할당 상품';
