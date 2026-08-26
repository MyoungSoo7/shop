-- 주문 시점 개인정보 제3자 제공 동의 이력.
--
-- 지금까지 이 저장소에는 동의라는 개념이 아예 없었다. 결제 화면에 체크박스를 그려 두고 체크해야
-- 버튼이 눌리게 하는 것이 전부인 구현이 흔한데(참고한 ssg 프런트도 정확히 그 형태다 —
-- order/payment.html 의 "개인정보 수집 및 이용, 제 3자 제공에 대한 고지(필수)" 체크박스는
-- 서버로 아무것도 보내지 않는다), 그러면 동의는 화면에만 존재하고 기록으로는 남지 않는다.
--
-- 남지 않으면 나중에 증명할 수 없다. 개인정보 보호법 제17조는 제3자에게 개인정보를 제공할 때
--   ① 제공받는 자 ② 제공 목적 ③ 제공하는 항목 ④ 제공받는 자의 보유·이용 기간
-- 을 알리고 동의를 받도록 하고, 같은 법 제22조는 필수 동의와 선택 동의를 구분해 받도록 한다.
-- "받았다"를 주장하려면 무엇을 보여 주고 받았는지가 남아 있어야 한다.
--
-- 표를 둘로 나눈 이유:
--   privacy_consent_terms  = 무엇을 고지하는가(문안). 버전이 붙고, 고칠 때는 새 버전을 만든다.
--   order_privacy_consents = 누가 언제 그 문안에 동의했는가(사실). 주문과 같은 트랜잭션에서 쓴다.
--
-- 사실 쪽에 문안을 스냅샷으로 한 벌 더 복사한다. 정규화 관점에서는 중복이지만, 이 표의 목적이
-- "그때 화면에 무엇이 적혀 있었는가"의 증명이라 문안 표를 참조만 해서는 목적을 달성하지 못한다.
-- 운영자가 현행 버전의 문장을 손보는 순간 과거의 동의가 다른 내용에 대한 동의로 읽히기 때문이다.
-- 그래서 외래키도 걸지 않는다 — 문안 행이 지워져도 동의 기록은 혼자 서야 한다.
-- body_sha256 은 전문의 지문이다. 나중에 문안 표의 같은 (code, version) 본문과 대조하면
-- 사후에 바뀌었는지 아닌지가 판정된다.

CREATE TABLE IF NOT EXISTS privacy_consent_terms (
    id             BIGSERIAL PRIMARY KEY,

    code           VARCHAR(60)  NOT NULL,
    version        INT          NOT NULL,
    consent_type   VARCHAR(30)  NOT NULL,
    title          VARCHAR(200) NOT NULL,

    -- 제17조가 요구하는 4가지 고지 항목.
    -- recipient 는 제3자 제공일 때만 뜻이 있다(수집·이용 동의에는 "제공받는 자"가 없다).
    recipient      VARCHAR(200),
    purpose        VARCHAR(500) NOT NULL,
    provided_items VARCHAR(500) NOT NULL,
    retention      VARCHAR(200) NOT NULL,

    body           TEXT         NOT NULL,
    body_sha256    VARCHAR(64)  NOT NULL,

    -- 필수/선택 구분(제22조). 필수는 거부하면 주문이 성립하지 않고, 선택은 거부해도 성립한다.
    -- 선택 동의를 거부한 사실도 기록으로 남는다 — 마케팅 수신 여부의 근거가 그 행이다.
    required       BOOLEAN      NOT NULL,

    effective_from TIMESTAMP    NOT NULL,
    effective_to   TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL,

    CONSTRAINT ck_privacy_consent_terms_type
        CHECK (consent_type IN ('COLLECTION_USE', 'THIRD_PARTY_PROVISION', 'PAYMENT_AGENCY', 'MARKETING')),
    -- 제3자 제공인데 "제공받는 자"가 비어 있으면 그 문안은 법이 요구하는 고지를 못 한 것이다.
    -- 문안을 만드는 시점에 막는다 — 화면에 뜬 뒤에 발견하면 이미 그 문안으로 동의를 받은 뒤다.
    CONSTRAINT ck_privacy_consent_terms_recipient
        CHECK (consent_type <> 'THIRD_PARTY_PROVISION' OR recipient IS NOT NULL),
    CONSTRAINT ck_privacy_consent_terms_period
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- 같은 문안의 같은 버전은 하나뿐이다. 버전을 올리지 않고 고치는 것을 막는 것이 이 인덱스의 일이다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_privacy_consent_terms_code_version
    ON privacy_consent_terms (code, version);

-- 결제 화면이 매번 묻는 질문 — "지금 유효한 문안은 무엇인가".
CREATE INDEX IF NOT EXISTS ix_privacy_consent_terms_effective
    ON privacy_consent_terms (consent_type, effective_from DESC);


CREATE TABLE IF NOT EXISTS order_privacy_consents (
    id             BIGSERIAL PRIMARY KEY,

    order_id       BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,

    terms_code     VARCHAR(60)  NOT NULL,
    terms_version  INT          NOT NULL,
    consent_type   VARCHAR(30)  NOT NULL,

    -- 동의했는가. 필수 항목이 false 인 행은 남을 수 없다 — 그런 주문은 애초에 만들어지지 않는다.
    agreed         BOOLEAN      NOT NULL,

    -- 그때 화면에 적혀 있던 고지 4종의 사본.
    recipient      VARCHAR(200),
    purpose        VARCHAR(500) NOT NULL,
    provided_items VARCHAR(500) NOT NULL,
    retention      VARCHAR(200) NOT NULL,
    body_sha256    VARCHAR(64)  NOT NULL,

    -- 동의 시각은 서버가 찍는다. 클라이언트가 보낸 시각을 믿으면 증명하려는 그 사실을
    -- 증명 대상이 스스로 적는 셈이 된다.
    agreed_at      TIMESTAMP    NOT NULL,
    -- IPv6 최대 표기 45자. 프록시 뒤라 정확하지 않을 수 있어 보조 증거로만 쓴다.
    ip_address     VARCHAR(45),
    created_at     TIMESTAMP    NOT NULL,

    CONSTRAINT ck_order_privacy_consents_type
        CHECK (consent_type IN ('COLLECTION_USE', 'THIRD_PARTY_PROVISION', 'PAYMENT_AGENCY', 'MARKETING')),
    CONSTRAINT ck_order_privacy_consents_version
        CHECK (terms_version > 0)
);

-- 한 주문에서 같은 문안에 두 번 동의할 수는 없다. 재시도·더블클릭이 이력을 부풀리는 것을 막는다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_order_privacy_consents_order_code
    ON order_privacy_consents (order_id, terms_code);

-- 주문 화면에서 "이 주문에서 무엇에 동의했는가"를 읽는 축.
CREATE INDEX IF NOT EXISTS ix_order_privacy_consents_order
    ON order_privacy_consents (order_id);

-- 정보주체가 "내가 언제 무엇에 동의했는지 보여 달라"고 할 때 읽는 축(열람 요구권).
CREATE INDEX IF NOT EXISTS ix_order_privacy_consents_user
    ON order_privacy_consents (user_id, agreed_at DESC);

-- 문안을 고친 뒤 "옛 버전으로 동의한 사람이 아직 남아 있는가"를 세는 축.
CREATE INDEX IF NOT EXISTS ix_order_privacy_consents_terms
    ON order_privacy_consents (terms_code, terms_version);

COMMENT ON TABLE privacy_consent_terms IS
    '동의 문안 카탈로그 — 버전이 붙는다. 문장을 고칠 때는 행을 고치지 않고 새 버전을 만든다.';
COMMENT ON TABLE order_privacy_consents IS
    '주문 시점 동의 이력 — 주문 생성과 같은 트랜잭션에서 쓰인다. 동의 없는 주문은 존재할 수 없다.';
COMMENT ON COLUMN order_privacy_consents.body_sha256 IS
    '동의 당시 문안 전문의 SHA-256. 문안 표의 같은 (code, version) 과 대조하면 사후 변경이 드러난다.';
COMMENT ON COLUMN order_privacy_consents.agreed IS
    '필수 항목은 여기 false 가 올 수 없다(주문이 거절된다). 선택 항목의 false 는 "물었고 거절했다"는 기록이다.';


-- 초기 문안 3종. 문장 자체는 운영자의 것이라 이 마이그레이션은 "무엇을 고지해야 하는가"의 뼈대만
-- 세운다. 보유기간 문구의 5년은 전자상거래 등에서의 소비자보호에 관한 법률 제6조와 같은 법
-- 시행령 제6조가 정한 계약·청약철회 기록의 보존기간을 옮긴 것이다.
--
-- body_sha256 은 body 에서 계산해 채운다. 손으로 적은 해시는 첫 오타부터 영원히 틀린다.
INSERT INTO privacy_consent_terms
    (code, version, consent_type, title, recipient, purpose, provided_items, retention,
     body, body_sha256, required, effective_from, created_at)
SELECT code, version, consent_type, title, recipient, purpose, provided_items, retention,
       body, encode(sha256(body::bytea), 'hex'), required, NOW(), NOW()
FROM (VALUES
    ('COLLECTION_USE_ORDER', 1, 'COLLECTION_USE',
     '주문 처리를 위한 개인정보 수집·이용 동의',
     NULL,
     '주문 접수, 대금 결제, 재화의 공급, 청약철회 및 소비자 불만 처리',
     '주문자 이름, 휴대전화번호, 이메일 주소, 받는 분 이름·휴대전화번호·주소',
     '계약 또는 청약철회 등에 관한 기록 5년 (전자상거래법 제6조)',
     '회사는 주문의 접수·결제·배송과 그에 따른 민원 처리를 위하여 위 항목을 수집·이용합니다. '
       || '귀하는 동의를 거부할 수 있으나, 거부하는 경우 주문을 진행할 수 없습니다.',
     TRUE),

    ('THIRD_PARTY_DELIVERY', 1, 'THIRD_PARTY_PROVISION',
     '배송을 위한 개인정보 제3자 제공 동의',
     '주문 상품의 배송을 수행하는 배송업체',
     '주문 상품의 배송, 배송 현황 조회, 배송 관련 문의 및 사고 처리',
     '받는 분 이름, 받는 분 휴대전화번호, 배송지 주소, 배송 메모',
     '배송 완료 후 90일 (관계 법령에 보존 의무가 있는 경우 해당 기간)',
     '회사는 주문하신 상품을 배송하기 위하여 위 항목을 배송업체에 제공합니다. '
       || '제공받는 배송업체는 주문 건별로 지정되며, 주문 상세 화면에서 확인하실 수 있습니다. '
       || '귀하는 동의를 거부할 수 있으나, 거부하는 경우 상품을 배송할 수 없어 주문을 진행할 수 없습니다.',
     TRUE),

    ('MARKETING_MESSAGE', 1, 'MARKETING',
     '광고성 정보 수신 동의 (선택)',
     NULL,
     '신규 상품·혜택·이벤트 안내 등 광고성 정보의 전송',
     '휴대전화번호, 이메일 주소',
     '동의 철회 시 또는 회원 탈퇴 시까지',
     '회사는 위 항목을 이용하여 광고성 정보를 전송합니다. '
       || '이 동의는 선택 사항이며, 거부하시더라도 주문과 서비스 이용에는 아무런 영향이 없습니다. '
       || '동의하신 뒤에도 언제든지 철회하실 수 있습니다.',
     FALSE)
) AS seed(code, version, consent_type, title, recipient, purpose, provided_items, retention,
          body, required)
WHERE NOT EXISTS (
    SELECT 1 FROM privacy_consent_terms t
     WHERE t.code = seed.code AND t.version = seed.version
);
