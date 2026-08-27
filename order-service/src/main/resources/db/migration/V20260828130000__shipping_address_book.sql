-- ============================================================
-- V20260828130000 : 배송지 주소록
--
-- 이식 대상이던 레거시(TBL_MEMBER_DELIVERY_PLACE)에는 결함이 셋 있었고, 셋 다 애플리케이션
-- 코드가 아니라 **스키마가 비어 있어서** 생긴 것이다. 그래서 여기서 막는다.
--
-- (1) 식별자를 SELECT 로 계산했다.
--         IDX   <- (SELECT NVL(MAX(IDX), 0) + 1 FROM TBL_MEMBER_DELIVERY_PLACE)     -- 전역
--         ADDNO <- (SELECT MAX(ADDNO) + 1 FROM ... WHERE MBID = ? AND PTNCODE = ?)  -- 회원별
--     두 트랜잭션이 같은 값을 읽는 것을 막는 장치가 없다. 동시에 배송지를 넣으면 같은 IDX 가
--     나오고, 그러면 IDX 로 잡는 수정·삭제가 남의 줄을 건드린다. 여기서는 BIGSERIAL 로 DB 가
--     발급한다. ADDNO(회원별 일련번호)는 아예 만들지 않는다 — 정렬은 기본 여부와 시각으로
--     충분하고, 없는 칼럼은 어긋날 수 없다.
--
-- (2) 기본 배송지가 0개나 2개가 될 수 있었다.
--     레거시는 "전부 S 로 내린다"(updateDeliveryDefault)와 "하나를 D 로 올린다"
--     (updateDeliveryInfo, cmmAdtp='D')가 **서로 다른 요청**이었고, 유일 제약도 없었다.
--     둘 사이에서 실패하면 기본이 하나도 없는 상태로 남는데, 기본 배송지를 읽는 조회는
--     WHERE ADTP = 'D' 라 **아무것도 돌려주지 않는다**. 주문서의 배송지 칸이 빈 채로 뜬다.
--     반대로 내리기가 빠지면 두 줄이 동시에 기본이 되고, 그때 어느 쪽이 실릴지는 정렬 운이다.
--     아래 부분 유일 인덱스가 "2개"를 DB 에서 불가능하게 만든다. "0개"는 제약으로 표현할 수
--     없으므로(빈 주소록도 정상이다) 서비스가 책임진다 — 첫 줄은 기본으로 들어가고, 기본을
--     지우면 남은 것 중 하나가 승격한다. 도메인 테스트가 그 둘을 붙든다.
--
-- (3) 별칭 칸에 등록 시엔 이름이, 수정 시엔 별칭이 들어갔다.
--         INSERT ... ADDNICKNAME <- #{cmmName}          -- 받는 사람 이름
--         UPDATE ... ADDNICKNAME  = #{cmmAddnickname}   -- 별칭
--     등록할 때 "회사"라고 적어도 저장되는 것은 받는 사람 이름이었다. 한 번 수정하기 전까지는
--     별칭이 이름의 사본이라, 목록이 전부 같은 글자로 보인다. 칸을 하나로 두고 출처도 하나로
--     둔다 — label 과 recipient_name 은 서로 다른 뜻이고 서로 덮어쓰지 않는다.
--
-- 암호화는 하지 않는다. 레거시는 칼럼마다 XX1.ENC_VARCHAR2_INS(...) 를 불러 Oracle 패키지에
-- 암·복호를 맡겼는데, 이 저장소에는 그 패키지가 없고 같은 것을 흉내 내면 키 관리 없는 자체
-- 암호가 된다. 개인정보 보호는 접근 통제(소유자 대조)와 배포 환경의 저장 암호화가 맡는다.
-- ============================================================

CREATE TABLE IF NOT EXISTS opslab.shipping_address_book (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    label          VARCHAR(50)  NOT NULL,   -- 별칭('집', '회사'). 받는 사람 이름과 다른 뜻이다
    recipient_name VARCHAR(100) NOT NULL,
    phone          VARCHAR(40)  NOT NULL,
    postal_code    VARCHAR(20)  NOT NULL,
    address1       VARCHAR(255) NOT NULL,
    address2       VARCHAR(255),
    delivery_memo  VARCHAR(255),
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 회원당 기본 배송지는 최대 하나. 부분 유일 인덱스라 FALSE 인 줄끼리는 얼마든지 공존한다.
-- 이것이 있으면 "올리기 전에 내린다"를 잊은 코드가 조용히 통과하지 못하고 그 자리에서 터진다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_shipping_address_book_user_default
    ON opslab.shipping_address_book (user_id) WHERE is_default;

-- 목록은 언제나 "내 주소록을 기본 먼저, 그 다음 최근 순으로" 다.
CREATE INDEX IF NOT EXISTS idx_shipping_address_book_user
    ON opslab.shipping_address_book (user_id, is_default DESC, created_at DESC);

COMMENT ON TABLE opslab.shipping_address_book IS
    '회원별 배송지 주소록. 기본 배송지는 부분 유일 인덱스로 최대 한 줄이며, '
    '비어 있지 않은 주소록에 기본이 반드시 하나 있게 하는 것은 애플리케이션의 책임이다.';

COMMENT ON COLUMN opslab.shipping_address_book.label IS
    '사용자가 붙인 별칭. 레거시는 등록 시 이 칸에 받는 사람 이름을 넣고 수정 시에만 별칭을 넣어, '
    '한 번 수정하기 전까지 별칭이 이름의 사본이었다. 여기서는 받는 사람 이름과 절대 섞지 않는다.';

COMMENT ON COLUMN opslab.shipping_address_book.is_default IS
    '기본 배송지 여부. 레거시의 ADTP(D/S) 자리다. 문자 코드 대신 불리언인 것은 값이 둘뿐이고 '
    '부분 유일 인덱스의 조건으로 그대로 쓸 수 있기 때문이다.';
