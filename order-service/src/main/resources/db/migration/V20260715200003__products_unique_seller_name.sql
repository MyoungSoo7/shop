-- V20260715200003: products 유니크 교정 — UNIQUE(name) → UNIQUE(seller_id, name)
--
-- 배경(DB 설계 리뷰 지적):
--   V10 은 name 에 글로벌 UNIQUE 를 걸었다(products_name_key). 그러나 V31 에서 상품에 seller_id 가
--   붙으며 멀티셀러 마켓플레이스가 되었고, "서로 다른 판매자가 같은 상품명을 쓸 수 없다"는
--   글로벌 UNIQUE(name) 은 도메인 모순이다(예: 두 셀러가 각자 '기본 티셔츠' 를 팔 수 없음).
--
-- 조치: 글로벌 UNIQUE(name) 을 제거하고 판매자 범위 UNIQUE(seller_id, name) 로 교체한다.
--       (같은 셀러가 같은 이름의 상품을 중복 등록하는 것만 막는다.)
--
-- seller_id NULL 처리 방침:
--   PostgreSQL 은 UNIQUE 에서 NULL 을 서로 구별되는 값으로 취급하므로, seller_id 가 NULL 인
--   레거시/미할당 상품은 이름이 같아도 충돌하지 않는다(중복 허용). 이는 의도된 완화 —
--   미할당 상품의 유일성은 판매자 등록 플로우가 seller_id 를 채우는 시점에 확보된다.
--   기존 시드 상품은 V31 에서 전량 seed_manager 에 귀속(seller_id NOT NULL)되었고 이름도 서로
--   달라 UNIQUE(seller_id, name) 을 즉시 만족한다 → 교체 시 위반 없음.
--
-- 참고: 기존 ON CONFLICT (name) 을 쓰는 시드(V17·V21·V20260711)는 모두 본 마이그레이션보다
--       앞서 이미 적용 완료되어, 글로벌 UNIQUE 제거가 소급 실패를 일으키지 않는다.
--       런타임 upsert 코드는 name 기준 ON CONFLICT 를 사용하지 않는다(확인 완료).

-- 1) 글로벌 UNIQUE(name) 제약 제거 (V10 인라인 UNIQUE 의 암묵적 제약명)
ALTER TABLE opslab.products
    DROP CONSTRAINT IF EXISTS products_name_key;

-- 2) 판매자 범위 UNIQUE(seller_id, name) 추가
ALTER TABLE opslab.products
    ADD CONSTRAINT uq_products_seller_name UNIQUE (seller_id, name);

COMMENT ON CONSTRAINT uq_products_seller_name ON opslab.products IS
    '판매자 범위 상품명 유일성. seller_id NULL(미할당)은 중복 허용(PostgreSQL NULL distinct). 글로벌 UNIQUE(name) 대체.';

-- idx_products_name(비유니크, name 검색용, V10)은 이름 단건 조회에 여전히 유효하므로 유지한다.
