-- =====================================================================================
-- V1: seller-service 자체 DB(lemuel_seller) 코어 — 셀러 백오피스
--
-- 이 스키마는 partner-service 와 성격이 정반대다. 파트너 콘솔에는 원본이 하나도 없었지만
-- (전부 사본, 쓰기 API 0개), 여기에는 **셀러가 직접 쓰는 원본이 둘** 있다:
--   · product_submissions      — 상품 등록/수정 신청서
--   · seller_shipment_requests — 송장(운송장) 등록 요청
-- 나머지는 화면을 그리기 위한 사본이고, 사본 쪽 설계 규칙은 파트너와 같다 —
-- **순서 독립**(프로젝션 간 FK 없음, 조회는 전부 LEFT JOIN).
--
-- ★ 왜 신청서가 원본이고 상품이 원본이 아닌가
--   카탈로그 상품(products) 과 배송(shipments) 의 오너는 order-service 다. 여기서 그 원장에
--   직접 쓰면 DB-per-service 는 이름만 남는다. 그래서 이 서비스는 **신청서** 라는 자기 원본을
--   갖고, 승인되면 lemuel.seller.product_approved 로 "등록해 달라" 는 요청만 낸다.
--   실제 상품번호는 order-service 가 등록한 뒤 lemuel.product.registered 로 돌아오고, 그때
--   product_submissions.product_id 에 찍힌다. 그전까지 신청서는 "승인됨, 상품번호 대기" 다.
--   이 두 단계를 한 칸으로 합치면(승인=상품번호 존재) 승인은 됐는데 카탈로그 등록이 실패한
--   상태를 표현할 방법이 사라진다.
--
-- ★ 레퍼런스(ssgb2e-outbackoffice)에서 가져온 것과 버린 것
--   가져온 것: 상태 어휘. PRST 2 대기 / 1 판매중 / 3 반려 → SUBMITTED / APPROVED / REJECTED,
--   SELLPRODUCTUPDATE 'C' 신규대기 / 'Y' 수정대기 → submission_type NEW / UPDATE,
--   PRODUCTVIEWYN → display_visible.
--   버린 것: 세션 기반 신원(CommonUtil.loginInfo()). 여기서 셀러 신원은 JWT subject 에서만 온다.
--   요청 본문·파라미터의 sellerId 는 어떤 경로로도 신뢰하지 않는다(CLAUDE.md IDOR 규칙) —
--   믿는 순간 남의 셀러 ID 를 적어 넣는 것만으로 남의 상품과 주문이 열린다.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 상품 등록/수정 신청서 — 이 서비스의 원본 ①
--
-- 상태 전이는 한 방향이다:
--   DRAFT --제출--> SUBMITTED --승인--> APPROVED --(product.registered)--> product_id 확정
--                            \--반려--> REJECTED --수정 후 재제출--> SUBMITTED
--
-- submission_type
--   NEW    = 카탈로그에 없는 새 상품. 승인되면 order-service 가 상품을 **생성**한다.
--   UPDATE = 이미 팔고 있는 상품의 수정 신청. base_product_id 가 그 대상이고, 승인되면
--            order-service 가 그 상품을 **갱신**한다.
--   레퍼런스가 이 둘을 한 테이블의 플래그로 구분한 것을 그대로 따랐다. 분리하면 심사 화면이
--   두 벌이 되고 "대기 건수" 를 세는 곳마다 두 번 세게 된다.
--
-- price 는 NUMERIC 이다. 돈에 double 을 쓰지 않는다(CLAUDE.md).
--
-- ★ seller_id 를 신청서에 박아 두는 이유
--   심사·조회의 모든 필터가 이 컬럼 하나로 끝나야 한다. 조직 → 셀러를 매번 조인해서 풀면,
--   조직 프로젝션이 아직 안 도착한 셀러의 신청서가 조회에서 통째로 사라진다(순서 의존).
--   신청 시점에 확정해 복사해 둔다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product_submissions (
    submission_id     BIGSERIAL     PRIMARY KEY,
    seller_id         BIGINT        NOT NULL,
    organization_id   BIGINT        NOT NULL,
    created_by_user_id BIGINT       NOT NULL,

    submission_type   VARCHAR(20)   NOT NULL DEFAULT 'NEW',
    -- UPDATE 신청일 때 수정 대상 카탈로그 상품. NEW 면 NULL 이다.
    base_product_id   BIGINT,

    name              VARCHAR(300)  NOT NULL,
    description       TEXT,
    price             NUMERIC(19,2) NOT NULL,
    stock             INTEGER       NOT NULL DEFAULT 0,
    category          VARCHAR(100),
    image_url         VARCHAR(500),
    -- 레퍼런스의 PRODUCTVIEWYN. 승인돼도 노출을 끄고 시작할 수 있다.
    display_visible   BOOLEAN       NOT NULL DEFAULT TRUE,

    status            VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    reject_reason     VARCHAR(500),

    -- 승인 뒤 lemuel.product.registered 로 되돌아온 카탈로그 상품번호. 승인 직후에는 NULL 이고,
    -- 화면은 그 상태를 "등록 처리 중" 으로 보여 준다. 승인과 같은 칸으로 합치지 않는 이유가 이것이다.
    product_id        BIGINT,

    submitted_at      TIMESTAMPTZ,
    decided_at        TIMESTAMPTZ,
    decided_by_user_id BIGINT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_submission_status
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_submission_type
        CHECK (submission_type IN ('NEW', 'UPDATE')),
    -- 가격·재고에 음수가 들어가는 경로를 스키마에서 닫는다. 애플리케이션 검증만 두면
    -- 이벤트 재처리·수동 보정 같은 옆문으로 음수가 들어온다.
    CONSTRAINT chk_submission_price CHECK (price >= 0),
    CONSTRAINT chk_submission_stock CHECK (stock >= 0),
    -- 반려에는 사유가 반드시 있어야 한다. 사유 없는 반려는 셀러가 무엇을 고쳐야 하는지 모른다.
    CONSTRAINT chk_submission_reject_reason
        CHECK (status <> 'REJECTED' OR reject_reason IS NOT NULL),
    -- UPDATE 신청은 대상 상품이 있어야 성립한다.
    CONSTRAINT chk_submission_base_product
        CHECK (submission_type <> 'UPDATE' OR base_product_id IS NOT NULL)
);

-- 셀러의 신청서 목록(내 것만, 최신순) — 셀러 화면의 기본 조회.
CREATE INDEX IF NOT EXISTS idx_submissions_seller
    ON product_submissions (seller_id, created_at DESC);

-- 운영자 심사 대기열 — 제출된 순서대로. 부분 인덱스라 승인·반려된 과거 건이 인덱스를 불리지 않는다.
CREATE INDEX IF NOT EXISTS idx_submissions_pending
    ON product_submissions (submitted_at) WHERE status = 'SUBMITTED';

-- 되돌아온 product.registered 를 신청서에 찍을 때의 역조회 경로.
CREATE INDEX IF NOT EXISTS idx_submissions_product
    ON product_submissions (product_id) WHERE product_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- 송장 등록 요청 — 이 서비스의 원본 ②
--
-- 셀러가 자기 주문에 운송장을 입력하면 여기 한 행이 쌓이고, lemuel.seller.shipment_registered
-- 로 order-service 에 출고를 요청한다. 실제 배송 상태는 order-service 의 shipments 가 갖는다.
--
-- ★ (order_id) UNIQUE 인 이유 — 그리고 그 한계를 여기 적어 두는 이유
--   order-service 의 ShippingUseCase.ship() 은 PENDING/READY 에서만 성립한다. 즉 한 주문의
--   출고는 사실상 한 번뿐이다. 여기서 여러 번 받아 두면 두 번째 요청은 저쪽에서 조용히
--   거절되는데, 셀러 화면에는 "등록됨" 으로 남아 **서로 다른 두 사실이 두 화면에 걸린다.**
--   그래서 두 번째 등록은 이 제약으로 요청 시점에 거절하고, 오등록 정정은 지금 경로가 없다는
--   것을 화면에도 그대로 적는다. 없는 기능을 있는 것처럼 보이게 하지 않는다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seller_shipment_requests (
    request_id       BIGSERIAL    PRIMARY KEY,
    order_id         BIGINT       NOT NULL,
    seller_id        BIGINT       NOT NULL,
    carrier          VARCHAR(50)  NOT NULL,
    tracking_number  VARCHAR(100) NOT NULL,
    requested_by_user_id BIGINT   NOT NULL,
    requested_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_shipment_request_order UNIQUE (order_id)
);

CREATE INDEX IF NOT EXISTS idx_shipment_requests_seller
    ON seller_shipment_requests (seller_id, requested_at DESC);

-- -------------------------------------------------------------------------------------
-- 셀러 조직 — lemuel.organization.created 프로젝션 (사본)
--
-- partner-service 와 같은 규칙이다. seller_id 는 type=SELLER 조직의 externalRef 마지막
-- 하이픈 뒤를 숫자로 읽어 파생하고, 숫자로 읽히지 않으면 NULL 로 둔다 — 파싱 실패를 0 이나
-- -1 로 메우면 서로 다른 조직이 같은 셀러로 뭉쳐 **남의 상품과 주문이 보인다.**
-- CORPORATE 조직은 셀러가 아니라 seller_id 가 없고, 그 조직 사용자는 이 백오피스를 못 쓴다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seller_organizations (
    organization_id BIGINT       PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    org_type        VARCHAR(20)  NOT NULL,
    external_ref    VARCHAR(100),
    seller_id       BIGINT,
    owner_user_id   BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_seller_org_type CHECK (org_type IN ('SELLER', 'CORPORATE'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_seller_orgs_seller_id
    ON seller_organizations (seller_id) WHERE seller_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- 구성원 — organization.member_joined / member_role_changed / member_removed 프로젝션 (사본)
--
-- ★ 이 테이블이 이 서비스의 인가 근거 전부다. 그런데 파트너와 달리 여기서는 인가가 조회만
--   여는 것이 아니라 **쓰기** 를 연다. 남의 조직 멤버가 여기 잘못 들어오면 그 사람이 남의
--   이름으로 상품을 등록하고 남의 주문을 출고 처리한다. 그래서 역할별 권한을 도메인에서
--   한 번 더 갈라 둔다(신청서 작성은 STAFF 이상, 제출은 MANAGER 이상 — SellerScope 참조).
--
-- PK 가 membership_id 인 이유는 파트너와 같다: 나갔다 다시 들어오면 (조직, 사용자) 는 같고
-- 멤버십만 새로 발급된다. (조직, 사용자) 를 PK 로 잡으면 늦게 도착한 옛 removed 이벤트가
-- **새 멤버십을 지운다.**
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seller_members (
    membership_id   BIGINT      PRIMARY KEY,
    organization_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    role            VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_seller_member_role   CHECK (role IN ('OWNER', 'MANAGER', 'STAFF')),
    CONSTRAINT chk_seller_member_status CHECK (status IN ('ACTIVE', 'REMOVED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_seller_members_active
    ON seller_members (organization_id, user_id) WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_seller_members_user
    ON seller_members (user_id) WHERE status = 'ACTIVE';

-- -------------------------------------------------------------------------------------
-- 확정 매출 — lemuel.payment.captured 프로젝션 (사본, 결제 1건 = 1행)
--
-- ★ 파트너 콘솔에서와 똑같은 제약이 여기도 그대로 있다. sellerId 를 실어 오는 이벤트가
--   payment.captured 뿐이라(ADR 0020), **결제가 확정되지 않은 주문은 셀러 주문 화면에
--   나타나지 않는다.** 이건 버그가 아니라 현재 계약의 한계이고, 화면에도 그렇게 적는다.
--   출고 대상 주문 목록이 이 테이블 위에 서 있다는 뜻이기도 하다 — 미결제 주문은 출고 대상이
--   아니니 실무적으로는 대체로 맞지만, "맞아서" 가 아니라 "그것밖에 없어서" 라는 걸 남긴다.
--
-- captured_at 을 TIMESTAMP(존 없음) 로 받는 이유도 파트너와 같다. 프로듀서가 LocalDateTime
-- 으로 싣기 때문에 TIMESTAMPTZ 로 받으면 존을 붙이는 쪽에서 9시간이 조용히 밀린다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seller_sales (
    payment_id       BIGINT        PRIMARY KEY,
    order_id         BIGINT        NOT NULL,
    seller_id        BIGINT,
    amount           NUMERIC(19,2) NOT NULL,
    payment_method   VARCHAR(30),
    captured_at      TIMESTAMP     NOT NULL,
    sale_date        DATE          NOT NULL,
    -- capturedAt 이 이벤트에 없어 수신 시각으로 채운 행. 금액은 정확하고 날짜만 흔들리는데,
    -- 하필 자정 언저리면 주문이 하루 옆으로 간다. 그 사실을 행에 남겨 두지 않으면 셀러는
    -- 틀린 날짜를 정확한 값으로 읽는다 — 출고 기한을 그 날짜로 세는 순간 실제 손해가 난다.
    captured_at_estimated BOOLEAN  NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 셀러 주문 목록의 주 접근 경로: 내 셀러의 최근 주문.
CREATE INDEX IF NOT EXISTS idx_seller_sales_seller_date
    ON seller_sales (seller_id, sale_date DESC) WHERE seller_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_seller_sales_order
    ON seller_sales (order_id);

-- -------------------------------------------------------------------------------------
-- 환불 — lemuel.payment.refunded 프로젝션 (사본)
--
-- 결제 행을 직접 깎지 않는다. 환불이 결제보다 먼저 도착할 수 있고(다른 토픽), 그때 결제 행은
-- 아직 없다. 별도로 쌓고 조회 시점에 LEFT JOIN 으로 빼면 순서와 무관해진다.
--
-- 셀러 화면에서 이 테이블의 쓸모는 금액보다 **상태** 다. 환불된 주문은 출고 대상이 아니다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seller_refunds (
    payment_id     BIGINT        NOT NULL,
    refund_key     VARCHAR(64)   NOT NULL,
    order_id       BIGINT        NOT NULL,
    refund_amount  NUMERIC(19,2) NOT NULL DEFAULT 0,
    refunded_total NUMERIC(19,2),
    occurred_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    PRIMARY KEY (payment_id, refund_key)
);

CREATE INDEX IF NOT EXISTS idx_seller_refunds_order
    ON seller_refunds (order_id);

-- -------------------------------------------------------------------------------------
-- 주문 — lemuel.order.created 프로젝션 (사본)
--
-- 이 이벤트에는 sellerId 가 없다. 셀러에게 보이는 경로는 오직 seller_sales(결제) 를 통해
-- order_id 로 조인하는 것뿐이고, 이 테이블은 상품 ID·주문 시각·주문 상태를 채우는 보조다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seller_orders (
    order_id     BIGINT        PRIMARY KEY,
    user_id      BIGINT        NOT NULL,
    product_id   BIGINT,
    status       VARCHAR(30)   NOT NULL,
    amount       NUMERIC(19,2) NOT NULL,
    ordered_at   TIMESTAMP,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_seller_orders_product
    ON seller_orders (product_id) WHERE product_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- 카탈로그 상품 — lemuel.product.changed / lemuel.product.registered 프로젝션 (사본)
--
-- 두 가지 용도가 겹친다.
--   ① 주문 목록에서 상품 ID 대신 이름을 보여 준다(파트너와 같은 용도).
--   ② 승인된 신청서가 카탈로그에 **실제로 실렸는지** 를 셀러 화면이 확인하는 근거다.
--      product.registered 에는 submission_id 가 실려 있어, 여기 행이 생겼다는 것 자체가
--      "등록 완료" 의 증거가 된다.
--
-- name 은 nullable 이다. product.changed 계약상 null 이 허용되고(required 이되 nullable),
-- 이름이 없으면 화면은 상품 ID 로 대체 표기한다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seller_products (
    product_id    BIGINT       PRIMARY KEY,
    name          VARCHAR(300),
    -- 이 상품이 어느 신청서에서 나왔는지. product.registered 로만 채워지므로, 이 서비스를
    -- 거치지 않고 운영자가 직접 만든 상품은 NULL 이다.
    submission_id BIGINT,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_seller_products_submission
    ON seller_products (submission_id) WHERE submission_id IS NOT NULL;
