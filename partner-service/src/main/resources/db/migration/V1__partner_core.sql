-- =====================================================================================
-- V1: partner-service 자체 DB(lemuel_partner) 코어 — 파트너 백오피스 읽기 프로젝션
--
-- 이 스키마에는 **원본이 하나도 없다.** 여기 있는 모든 행은 다른 서비스가 발행한 이벤트를
-- 받아 쌓은 사본이고, 이 서비스는 어떤 테이블에도 사용자 요청으로 쓰지 않는다(쓰기 API 0개).
-- 그래서 설계 기준이 일반 도메인 스키마와 다르다 — 정합성보다 **순서 독립성**이 먼저다.
--
-- ★ 왜 순서 독립인가
--   화면 한 장이 여러 토픽에서 온다. 베스트 상품 = payment.captured(누가 얼마) ⋈
--   order.created(주문→상품) ⋈ product.changed(상품→이름). Kafka 는 **토픽이 다르면 순서를
--   보장하지 않는다.** 결제 이벤트가 주문 이벤트보다 먼저 도착하는 일이 정상적으로 일어난다.
--   그래서 이 스키마에는 프로젝션 테이블 사이에 FK 가 없고, 조회는 전부 LEFT JOIN 이다.
--   형제가 아직 안 온 행은 이름이 비어 보일 뿐 사라지지 않는다. FK 를 걸면 그 행은 아예
--   적재에 실패하고, 뒤늦게 온 형제가 그것을 되살릴 방법이 없다.
--
-- ★ 개인정보를 담지 않는다
--   구매자는 어느 이벤트에도 이름·연락처·주소로 나오지 않는다. 숫자 user_id 뿐이다.
--   레퍼런스(ssgb2e-ptnbackoffice)의 마스킹·다운로드 사유 입력은 그래서 옮기지 않았다 —
--   보호할 대상이 없는 통제는 보호받고 있다는 착각만 만든다. 없는 것은 없다고 둔다.
--   (뒤집어 말하면, 나중에 어떤 이벤트가 PII 를 싣기 시작하면 그때 통제를 새로 설계해야 한다.)
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 파트너(입점 조직) — lemuel.organization.created 프로젝션
--
-- seller_id 는 type=SELLER 인 조직의 externalRef 에서 파생한다. 값이 그대로 셀러 ID 인
-- 경우도 있고 "SELLER-777" 처럼 접두사를 달고 오는 경우도 있어, 마지막 하이픈 뒤를 숫자로
-- 읽는다(하이픈이 없으면 문자열 전체가 그 자리다). CORPORATE 의 externalRef 는 stockCode 라
-- 셀러가 아니다 — 그 조직의 매출 화면이 비어 있는 것이 맞다.
-- 숫자로 읽히지 않는 externalRef 는 seller_id 를 NULL 로 둔다 — 파싱 실패를 0 이나 -1 로
-- 메우면 서로 다른 조직이 같은 셀러로 뭉쳐 **남의 매출이 보인다.**
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS partners (
    organization_id BIGINT       PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    org_type        VARCHAR(20)  NOT NULL,
    external_ref    VARCHAR(100),
    seller_id       BIGINT,
    owner_user_id   BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_partner_org_type CHECK (org_type IN ('SELLER', 'CORPORATE'))
);

-- 한 셀러는 한 조직에만 속한다. 이게 깨지면 권한 조회가 조직 두 개를 돌려주고,
-- 그 순간 어느 쪽에 보여줘야 하는지 코드가 결정할 수 없게 된다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_partners_seller_id
    ON partners (seller_id) WHERE seller_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- 파트너 구성원 — organization.member_joined / member_role_changed / member_removed 프로젝션
--
-- ★ 이 테이블이 이 서비스의 인가 근거 전부다. JWT 의 userId 로 여기를 조회해 조직을 얻고,
--   조직에서 seller_id 를 얻는다. 요청 파라미터의 sellerId 는 **어떤 경로로도 신뢰하지 않는다**
--   (CLAUDE.md IDOR 규칙). 파라미터를 믿으면 남의 셀러 ID 를 넣는 것만으로 남의 매출이 열린다.
--
-- PK 를 membership_id 로 두는 이유: 나갔다 다시 들어오면 (조직, 사용자) 는 같고 멤버십만 새로
-- 발급된다. (조직, 사용자) 를 PK 로 잡으면 재가입 이벤트가 기존 행을 덮어써 이전 이력이 사라지고,
-- 늦게 도착한 옛 removed 이벤트가 **새 멤버십을 지운다.**
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS partner_members (
    membership_id   BIGINT      PRIMARY KEY,
    organization_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    role            VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_partner_member_role   CHECK (role IN ('OWNER', 'MANAGER', 'STAFF')),
    CONSTRAINT chk_partner_member_status CHECK (status IN ('ACTIVE', 'REMOVED'))
);

-- 활성 멤버십은 (조직, 사용자) 당 하나. REMOVED 는 여러 개 남을 수 있다(재가입 이력).
CREATE UNIQUE INDEX IF NOT EXISTS uq_partner_members_active
    ON partner_members (organization_id, user_id) WHERE status = 'ACTIVE';

-- 인가 조회의 주 진입 경로: user_id → 활성 멤버십.
CREATE INDEX IF NOT EXISTS idx_partner_members_user
    ON partner_members (user_id) WHERE status = 'ACTIVE';

-- -------------------------------------------------------------------------------------
-- 확정 매출 — lemuel.payment.captured 프로젝션 (결제 1건 = 1행)
--
-- ★ 이 서비스가 "판 것"을 아는 유일한 경로다. sellerId 를 실어 오는 이벤트가 이것뿐이라
--   (ADR 0020 Event-Carried State Transfer), 결제가 확정되지 않은 주문은 파트너 화면에
--   나타나지 않는다. 이건 버그가 아니라 현재 계약의 한계이고, 화면에도 그렇게 적는다.
--
-- seller_id 가 NULL 인 행(셀러 미할당 결제)은 어느 파트너에게도 보이지 않는다 — 조회가 항상
-- seller_id 로 필터되기 때문이다. 지우지 않고 남기는 이유는, 나중에 셀러가 할당되어 재발행될 때
-- 같은 payment_id 로 덮어쓰면 그대로 살아나기 때문이다.
--
-- captured_at 은 프로듀서가 LocalDateTime(존 없음, 서버 로컬=KST)으로 싣는다. 그래서
-- TIMESTAMP(WITHOUT TIME ZONE) 로 받는다 — TIMESTAMPTZ 로 받으면 존을 붙이는 쪽에서 9시간이
-- 조용히 밀린다. sale_date 는 그 값의 날짜부분이며, 일자별 집계의 기준이다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS partner_sales (
    payment_id       BIGINT        PRIMARY KEY,
    order_id         BIGINT        NOT NULL,
    seller_id        BIGINT,
    amount           NUMERIC(19,2) NOT NULL,
    seller_tier      VARCHAR(20),
    settlement_cycle VARCHAR(20),
    payment_method   VARCHAR(30),
    captured_at      TIMESTAMP     NOT NULL,
    sale_date        DATE          NOT NULL,
    -- 이벤트에 capturedAt 이 없어 수신 시각으로 대체한 행. 자정 근처에서 하루가 밀릴 수 있어
    -- 표시하는 쪽이 사실을 알 수 있게 남긴다(집계에서 빼지는 않는다 — 금액은 정확하다).
    captured_at_estimated BOOLEAN  NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 대시보드·일자별 매출·엑셀의 공통 접근 경로: 내 셀러의 기간 매출.
CREATE INDEX IF NOT EXISTS idx_partner_sales_seller_date
    ON partner_sales (seller_id, sale_date) WHERE seller_id IS NOT NULL;

-- 주문 상세 화면(주문번호로 찾기) + order.created 프로젝션과의 조인.
CREATE INDEX IF NOT EXISTS idx_partner_sales_order
    ON partner_sales (order_id);

-- -------------------------------------------------------------------------------------
-- 환불 — lemuel.payment.refunded 프로젝션
--
-- ★ 결제 행을 직접 깎지 않는다. 환불 이벤트가 결제 이벤트보다 먼저 올 수 있고(다른 토픽),
--   그때 결제 행은 아직 없다. 별도 테이블에 쌓고 조회 시점에 LEFT JOIN 으로 빼면 순서와
--   무관해진다 — 늦게 온 결제가 이미 쌓인 환불을 자동으로 만난다.
--
-- refund_key 는 refundId(있으면) 또는 event_id(없으면)다. 둘 다 같은 환불에 대해 안정적이라
-- PK 가 3단 멱등의 3단계(도메인 UNIQUE)로 그대로 작동한다.
--
-- 금액 두 벌을 다 저장하는 이유: 계약상 refundAmount(이번 delta) 와 refundedAmount(누적) 중
-- 하나만 있어도 된다. 실효 환불액은 GREATEST(MAX(누적), SUM(delta)) 로 읽는다 —
-- 누적만 오면 MAX 가, delta 만 오면 SUM 이 맞고, 둘 다 오면 같은 값이라 어느 쪽이든 맞다.
-- 두 방식 모두 도착 순서와 무관하다는 게 이 식을 고른 이유다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS partner_refunds (
    payment_id     BIGINT        NOT NULL,
    refund_key     VARCHAR(64)   NOT NULL,
    order_id       BIGINT        NOT NULL,
    refund_amount  NUMERIC(19,2) NOT NULL DEFAULT 0,
    refunded_total NUMERIC(19,2),
    occurred_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    PRIMARY KEY (payment_id, refund_key)
);

CREATE INDEX IF NOT EXISTS idx_partner_refunds_order
    ON partner_refunds (order_id);

-- -------------------------------------------------------------------------------------
-- 주문 — lemuel.order.created 프로젝션
--
-- ★ 이 이벤트에는 sellerId 가 없다. 그래서 이 테이블 단독으로는 "누구의 주문인지" 를 모른다.
--   파트너에게 보이는 경로는 오직 partner_sales(결제) 를 통해서다 — order_id 로 조인해
--   상품 ID 와 주문 시각을 채우는 보조 테이블이다.
--   (product.changed 에도 sellerId 가 없어서 상품 → 셀러 역추적도 불가능하다. 계약을 넓히면
--    풀리지만 그건 order-service 를 건드리는 별건이다.)
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS partner_orders (
    order_id     BIGINT        PRIMARY KEY,
    user_id      BIGINT        NOT NULL,
    product_id   BIGINT,
    status       VARCHAR(30)   NOT NULL,
    amount       NUMERIC(19,2) NOT NULL,
    ordered_at   TIMESTAMP,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_partner_orders_product
    ON partner_orders (product_id) WHERE product_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- 상품 이름 — lemuel.product.changed 프로젝션
--
-- 베스트 상품 화면에서 ID 대신 이름을 보여주기 위한 것뿐이다. 계약상 name 은 null 이 허용되고
-- (required 이되 nullable), 이름이 없으면 화면은 상품 ID 로 대체 표기한다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS partner_products (
    product_id BIGINT       PRIMARY KEY,
    name       VARCHAR(300),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- -------------------------------------------------------------------------------------
-- 셀러 등급 — lemuel.seller.tier_changed 프로젝션 (현재 등급 스냅샷)
--
-- ★ 등급은 비소급이다(ADR 0031 · ADR 0014 §4). 정산 계산은 결제 시점 등급
--   (partner_sales.seller_tier 에 동봉된 값)을 쓰고, 이 테이블은 "지금 등급이 무엇인가" 만
--   답한다. 여기 값으로 과거 매출을 다시 계산하면 안 된다.
--
-- effective_from 을 함께 두고 그보다 이른 이벤트를 무시하는 이유는 재전달·재처리 때문이다.
-- 낡은 등급 변경이 최신 등급을 덮어쓰면 화면 등급이 과거로 되돌아간다.
--
-- reason='BACKFILL' 은 변경이 아니라 이미 확정된 등급의 재발행이다. 등급 값은 반영하되
-- "이때 바뀌었다" 로는 읽지 않는다 — 그러면 백필 시각이 변경일로 둔갑한다.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS partner_seller_tiers (
    seller_id      BIGINT      PRIMARY KEY,
    current_tier   VARCHAR(20) NOT NULL,
    effective_from DATE        NOT NULL,
    reason         VARCHAR(30) NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_partner_tier CHECK (current_tier IN ('NORMAL', 'VIP', 'STRATEGIC'))
);
