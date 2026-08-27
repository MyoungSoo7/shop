-- 문의(상품 문의 · 주문·배송 문의 · 1:1 문의).
--
-- 레거시는 이 셋을 서로 다른 테이블로 나눠 두었다(TBL_PRODUCT_QNA · TBL_PRODUCT_ONE · 상품요청).
-- 칼럼도 쿼리도 거의 같았고, 그래서 한쪽만 고쳐진 곳이 생겼다 — 예컨대 답변 상태를 판정하는
-- 서브쿼리는 상품문의 쪽에만 있다. 여기서는 한 테이블에 type 칼럼을 두고, 종류에 따라 달라지는
-- 것은 "무엇을 함께 요구하는가"(상품 · 주문) 하나로 좁힌다.
--
-- 바로잡은 것 넷:
--
-- 1) id 를 DB 가 발급한다. 레거시는 (SELECT NVL(MAX(ID)+1, 1) FROM TBL_PRODUCT_ONE) 로 다음
--    번호를 읽어서 넣었다. 두 요청이 같은 순간에 읽으면 같은 번호를 쓴다. 게다가 질문과 답변을
--    잇는 ID_NUM 이 그 값의 음수라, 번호가 겹치면 남의 문의에 답변이 붙는다.
--
-- 2) 답변을 별도 테이블로 뺀다. 레거시는 답변을 질문과 같은 테이블의 형제 행으로 넣고
--    ABS(ID_NUM) = 질문ID AND ID_DEPTH != 0 이라는 관례로 이었다. 관례는 DB 가 지켜 주지 않는다.
--    여기서는 외래키이고, 질문이 사라지면 답변도 CASCADE 로 함께 사라진다.
--
-- 3) 답변 상태(ANSWER_STATUS / ST) 칼럼이 없다. 답변 행의 유무가 곧 상태다. 레거시는 이 값을
--    질문 행에 저장했는데 답변 삭제 경로가 그것을 되돌리지 않아, 답변이 사라진 뒤에도 목록은
--    "답변 완료"라 말하고 상세를 열면 아무것도 없었다. 파생값을 저장하면 갱신 경로를 하나
--    빠뜨리는 순간 두 사실이 어긋난다.
--
-- 4) 비밀글(secret)이 있다. 상품 문의는 상품 페이지에 공개로 걸리는데 레거시에는 가릴 수단이
--    없었다 — 작성자 이름만 암호화해 두고 제목·본문은 그대로였다.
--
-- 개인정보는 애초에 받지 않는다. 레거시는 문의에 이름·휴대폰을 함께 저장하고(오라클 패키지
-- XX1.ENC_VARCHAR2_INS 로 암호화) 알림톡 발송에 썼다. 문의는 로그인해야 남길 수 있으므로
-- 연락처는 회원 정보에서 읽으면 된다 — 같은 사실을 두 곳에 두면 한쪽이 낡는다.

CREATE TABLE IF NOT EXISTS opslab.inquiries (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    type        VARCHAR(20) NOT NULL,               -- PRODUCT · ORDER · GENERAL
    product_id  BIGINT,                             -- PRODUCT 면 필수. 도메인이 강제한다
    order_id    BIGINT,                             -- ORDER 면 필수
    subject     VARCHAR(200)  NOT NULL,
    content     VARCHAR(4000) NOT NULL,
    secret      BOOLEAN     NOT NULL DEFAULT FALSE,
    asked_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS opslab.inquiry_answers (
    id          BIGSERIAL   PRIMARY KEY,
    inquiry_id  BIGINT      NOT NULL,
    answered_by BIGINT      NOT NULL,               -- 답변한 운영자
    content     VARCHAR(4000) NOT NULL,
    answered_at TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_inquiry_answers_inquiry
        FOREIGN KEY (inquiry_id) REFERENCES opslab.inquiries (id) ON DELETE CASCADE
);

-- 내 문의 목록은 언제나 "내 것을 최신순으로"다. 정렬까지 인덱스가 받아 준다.
CREATE INDEX IF NOT EXISTS ix_inquiries_user
    ON opslab.inquiries (user_id, asked_at DESC);

-- 상품 페이지의 공개 문의 목록.
CREATE INDEX IF NOT EXISTS ix_inquiries_product
    ON opslab.inquiries (product_id, asked_at DESC);

-- 답변 대기 목록은 "답변이 없는 것"을 NOT EXISTS 로 찾는다. 그 서브쿼리가 이 인덱스를 탄다.
CREATE INDEX IF NOT EXISTS ix_inquiry_answers_inquiry
    ON opslab.inquiry_answers (inquiry_id, answered_at);

COMMENT ON TABLE opslab.inquiries IS
    '문의. 상품 문의·주문 문의·1:1 문의를 type 으로 구분한다 — 레거시의 세 테이블을 하나로 합친 것.';

COMMENT ON COLUMN opslab.inquiries.secret IS
    '비밀글. 상품 문의에서만 뜻이 있다(다른 종류는 애초에 공개 목록이 없다). '
    '비밀글도 목록에서 줄은 남고 제목만 가려진다 — 감추면 작성자가 등록 여부를 확인할 수 없다.';

COMMENT ON COLUMN opslab.inquiries.product_id IS
    '상품 FK 를 걸지 않는다. 상품이 지워져도 문의와 그 답변은 남아야 한다 — CASCADE 로 사라지면 '
    '답변까지 함께 없어지고, 같은 질문이 반복되는 것을 막을 근거도 사라진다.';

COMMENT ON TABLE opslab.inquiry_answers IS
    '문의에 달린 답변. 질문의 자식이며 질문이 지워지면 함께 사라진다. '
    '답변 유무가 곧 답변 상태다 — 상태 칼럼은 어디에도 없다.';
