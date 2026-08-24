-- V32: 판매자 등급(SellerTier) + 정산별 수수료율 이력
--
-- T3-⑦(a) 차등 수수료의 선행 마이그레이션.
--
-- 1) users.seller_tier — 판매자 등급 (NORMAL/VIP/STRATEGIC)
-- 2) settlements.commission_rate — 정산 시점의 적용된 수수료율 (이력 보존).
--    기존 정산은 3% 로 백필. 향후 등급별 차등 수수료가 적용되면 해당 시점의 rate 가 저장된다.

ALTER TABLE opslab.users
    ADD COLUMN IF NOT EXISTS seller_tier VARCHAR(20) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE opslab.users
    ADD CONSTRAINT chk_users_seller_tier
        CHECK (seller_tier IN ('NORMAL', 'VIP', 'STRATEGIC'));

CREATE INDEX IF NOT EXISTS idx_users_seller_tier
    ON opslab.users (seller_tier)
    WHERE seller_tier <> 'NORMAL';

ALTER TABLE opslab.settlements
    ADD COLUMN IF NOT EXISTS commission_rate NUMERIC(5, 4) NOT NULL DEFAULT 0.0300;

-- 향후 과거 정산을 다시 계산할 일이 있을 때 당시 적용 rate 를 되추적할 수 있도록 인덱스는 두지 않는다
-- (조회 쿼리는 단건 정산 내부에서만 참조).

COMMENT ON COLUMN opslab.users.seller_tier IS '판매자 등급 — NORMAL(기본), VIP(할인), STRATEGIC(최고 할인)';
COMMENT ON COLUMN opslab.settlements.commission_rate IS '정산 생성 시점의 수수료율 (4자리 소수). 차등 수수료 이력 보존용.';
