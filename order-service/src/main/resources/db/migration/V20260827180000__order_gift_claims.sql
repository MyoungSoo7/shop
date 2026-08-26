-- 선물 주문 — 받는 사람이 자기 배송지를 직접 낸다.
--
-- 지금까지 남에게 물건을 보내려면 사는 사람이 받는 사람의 집 주소를 알아야 했다. 그래서 실제로는
-- "주소 좀 알려줘"를 카톡으로 먼저 물어보고, 그걸 받아 적어 주문서에 넣는 것이 유일한 방법이었다.
-- 세 가지가 무너진다:
--   ① 서프라이즈가 성립하지 않는다 — 주소를 물어보는 순간 선물인 걸 안다.
--   ② 받는 사람의 주소가 보내는 사람 계정의 배송지 목록에 남는다. 한 번 준 주소가 영구히
--      제3자의 주소록에 들어가는 것이라, 개인정보 관점에서 원래 있으면 안 되는 이전이다.
--   ③ 받아 적다 틀린다. 동·호수가 빠진 주소로 나간 배송의 뒷수습은 전부 사람이 한다.
--
-- 그래서 주문과 배송지를 시간축으로 떼어 놓는다. 주문은 배송지 없이 성립하고(orders.shipping_*
-- 는 이미 NULL 을 허용한다), 링크를 받은 사람이 본인확인을 거쳐 주소를 넣는 순간 주문에 주소가
-- 붙고 배송이 생긴다. 주문서에 주소가 "한 번만 쓰인다"는 기존 성질은 그대로다 — 쓰는 시점과
-- 쓰는 사람만 달라졌다.
--
-- token_hash 는 해시다. 이 토큰은 로그인 없이 남의 주문 화면을 여는 열쇠라, 평문으로 담으면
-- 이 표 한 벌이 새는 순간 살아 있는 모든 링크가 즉시 사용 가능한 상태가 된다. 같은 저장소의
-- password_reset_tokens 는 평문을 담고 있는데, 그 선례를 따라가지 않는다.

CREATE TABLE IF NOT EXISTS order_gift_claims (
    id                     BIGSERIAL PRIMARY KEY,
    order_id               BIGINT       NOT NULL,
    sender_user_id         BIGINT       NOT NULL,

    -- 받는 사람 — 회원이 아닐 수 있다. 그래서 user_id 가 아니라 이름·번호로 적힌다.
    recipient_name         VARCHAR(60)  NOT NULL,
    recipient_phone        VARCHAR(40)  NOT NULL,
    message                VARCHAR(200),

    -- SHA-256 hex = 64자. 128 은 해시 알고리즘을 바꿀 때를 위한 여유다.
    token_hash             VARCHAR(128) NOT NULL,
    status                 VARCHAR(20)  NOT NULL,

    -- 본인확인 6자리의 해시와 유효시각. 인증이 끝나면 둘 다 NULL 로 지운다 —
    -- 쓴 번호를 남겨 두면 유출 시 재사용된다.
    verification_code_hash VARCHAR(128),
    code_expires_at        TIMESTAMP,
    -- 6자리는 해시만으로 못 지킨다(100만 번이면 되돌아온다). 실제 방어는 이 카운터와 짧은 TTL 이다.
    verify_attempts        INT          NOT NULL DEFAULT 0,

    expires_at             TIMESTAMP    NOT NULL,
    created_at             TIMESTAMP    NOT NULL,
    verified_at            TIMESTAMP,
    claimed_at             TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL,

    CONSTRAINT ck_order_gift_claims_status
        CHECK (status IN ('PENDING', 'VERIFIED', 'CLAIMED', 'EXPIRED', 'CANCELED')),
    CONSTRAINT ck_order_gift_claims_attempts
        CHECK (verify_attempts >= 0)
);

-- 한 주문에 링크는 하나다. 둘이면 둘 다 배송지를 낼 수 있게 되고, 나중에 낸 쪽은 이미 주소가
-- 붙은 주문을 만나 실패한다 — 아무 잘못 없는 사람에게 원인 모를 오류로 보인다.
-- 응용 계층도 같은 검사를 하지만 동시 요청은 그 검사를 둘 다 통과하므로, 최종 방어선은 여기다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_order_gift_claims_order
    ON order_gift_claims (order_id);

-- 조회는 언제나 해시로 들어온다. 부분 인덱스로 좁히지 않는 이유는 만료·취소된 토큰도 찾아서
-- "끝난 선물입니다"를 정확히 보여 줘야 하기 때문이다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_order_gift_claims_token
    ON order_gift_claims (token_hash);

-- 소멸 배치가 훑는 축. 이미 끝난 건은 볼 이유가 없어 부분 인덱스로 잘라 둔다.
CREATE INDEX IF NOT EXISTS ix_order_gift_claims_expiry
    ON order_gift_claims (expires_at)
    WHERE status IN ('PENDING', 'VERIFIED');

CREATE INDEX IF NOT EXISTS ix_order_gift_claims_sender
    ON order_gift_claims (sender_user_id, created_at DESC);

COMMENT ON TABLE order_gift_claims IS
    '선물 주문의 수령 기록 — 받는 사람이 링크로 들어와 본인확인 후 자기 배송지를 내는 절차를 담는다.';
COMMENT ON COLUMN order_gift_claims.token_hash IS
    '링크 토큰의 SHA-256. 평문은 발급 순간에만 존재하고 어디에도 저장하지 않는다.';
COMMENT ON COLUMN order_gift_claims.verify_attempts IS
    '인증번호 오입력 횟수. 6자리를 지키는 실제 수단이라 초기화는 번호 재발급 때만 일어난다.';
COMMENT ON COLUMN order_gift_claims.expires_at IS
    '링크 유효기한. 만료 판정은 이 시각으로 하고 status=EXPIRED 는 배치가 남기는 기록일 뿐이다.';
