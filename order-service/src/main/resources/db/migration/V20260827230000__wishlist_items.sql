-- 찜(위시리스트).
--
-- 이식 대상이던 레거시의 찜 테이블에는 (회원, 상품) 유일 제약이 없었다. 담기는 "있으면 지우고
-- 없으면 넣는" 토글이었고, 그 판정과 삽입 사이에는 트랜잭션 경계가 없었다 — 하트를 두 번 빠르게
-- 누르거나 요청이 재전송되면 같은 상품이 두 줄로 들어갔다. 개수 배지는 그만큼 부풀었고, 빼기는
-- 한 줄만 지워서 목록에 남은 쪽이 계속 살아났다. 유일 제약은 그 경합을 애플리케이션 코드가 아니라
-- DB 가 끝내게 한다. 위반은 오류가 아니라 "이미 담겨 있음" 으로 읽어 멱등하게 처리한다.
--
-- id 도 애플리케이션이 SELECT NVL(MAX(..)+1, 0) 으로 계산하고 있었다. 동시 삽입 두 건이 같은
-- 값을 읽는 것을 막는 장치가 없다. 여기서는 BIGSERIAL 로 DB 가 발급한다.
--
-- 담을 때의 옵션·수량은 저장하지 않는다. 레거시는 담은 순간의 옵션 코드와 수량을 함께 얼려
-- 두었는데, 그 옵션이 나중에 사라지거나 가격이 바뀌어도 찜은 옛 값을 그대로 들고 있었다. 찜은
-- "이 상품이 궁금하다" 는 표시이지 주문서가 아니다 — 상품 정보는 조회 시점에 상품 쪽에서 읽는다.

CREATE TABLE IF NOT EXISTS opslab.wishlist_items (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT    NOT NULL,
    product_id  BIGINT    NOT NULL,
    added_at    TIMESTAMP NOT NULL DEFAULT NOW(),   -- 담은 시각. 목록의 기본 정렬 기준

    CONSTRAINT uk_wishlist_items_user_product UNIQUE (user_id, product_id)
);

-- 조회는 언제나 "내 찜을 최근에 담은 순으로" 다. 정렬까지 인덱스가 받아 준다.
-- (user_id, product_id) 유일 인덱스는 선두 칼럼이 같아 단건 존재 확인에도 쓰이므로 따로 만들지 않는다.
CREATE INDEX IF NOT EXISTS idx_wishlist_items_user_added
    ON opslab.wishlist_items (user_id, added_at DESC);

COMMENT ON TABLE opslab.wishlist_items IS
    '회원별 찜 목록. (user_id, product_id) 유일 — 같은 상품은 몇 번을 담아도 한 줄이다.';

COMMENT ON COLUMN opslab.wishlist_items.product_id IS
    '상품 FK 를 걸지 않는다. 상품이 지워져도 찜 줄은 남아야 "삭제된 상품" 이라고 말해 줄 수 있고, '
    'CASCADE 로 조용히 사라지면 사용자는 자기가 담았던 것이 무엇이었는지 알 방법이 없다.';

COMMENT ON COLUMN opslab.wishlist_items.added_at IS
    '담은 시각. 수정되지 않는다 — 다시 담아도 유일 제약에 걸려 기존 줄이 그대로 유지된다.';
