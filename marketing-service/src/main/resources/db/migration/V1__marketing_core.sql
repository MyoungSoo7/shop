-- =====================================================================================
-- V1: 이벤트 프로모션 코어 — 출석체크 · 럭키박스 · 보상 요청
--
-- 레거시(ssgb2e-front, Oracle/MyBatis)의 TBL_ATTENDANCE / TBL_ATTENDANCE_APPLY /
-- TBL_ATTENDANCE_SUCCESS / TBL_LUCKYBOX / TBL_LUCKYBOX_ITEM / TBL_LUCKYBOX_APPLY 6종을
-- PostgreSQL 로 옮긴 것이다. 컬럼을 1:1 로 베끼지 않고 세 가지를 바꿨다.
--
--   ① 코드값을 이름으로 바꿨다. 레거시는 EVENT_CON='N'/'Y'/'C', EDATE_TYPE='D'/'M',
--      EVENT_DAY_TYPE='ED'/'WD'/'WE', EVENT_ST='2'/'3' 처럼 의미가 값 안에 숨어 있었고,
--      그래서 SQL 을 읽는 사람마다 다르게 해석했다. 실제로 연속 출석일수를 세는 200줄짜리
--      윈도우 함수 CTE 는 'ED'·'WD' 분기만 있고 'WE' 도 ELSE 도 없어서, 주말 캠페인의
--      연속일수가 항상 0 이었다(에러가 아니라 0 이라 아무도 몰랐다). 그 계산은 이제 SQL 이
--      아니라 도메인(AttendanceStreak)에 있고 규칙은 CHECK 로 열거된다.
--
--   ② 중복 참여를 인덱스로 막는다. 레거시는 "오늘 참여했나" 를 SELECT 로 확인한 뒤 INSERT
--      했다 — 더블클릭 두 번이 같은 순간에 오면 둘 다 통과한다. 유니크 인덱스는 그 창을 없앤다.
--
--   ③ 마일리지(포인트)를 여기에 두지 않는다. 레거시는 이벤트 서비스가 mileageService 를
--      직접 호출해 원장에 썼다. 이 저장소에서 포인트 원장의 주인은 order-service 이고,
--      마케팅은 reward_grants 에 "요청했다"는 사실만 남긴 뒤 Kafka 로 요청을 낸다.
--      잔액은 한 곳에만 있어야 한다.
--
-- 캠페인 테이블을 출석·럭키박스로 나눈 것도 의도다. 공통 상위 테이블을 두면 조회마다 조인이
-- 붙는데, 두 캠페인이 공유하는 것은 이름·기간·상태·배너뿐이고 나머지는 전부 다르다.
-- =====================================================================================

-- ── 출석체크 캠페인 ────────────────────────────────────────────────────────────────
CREATE TABLE attendance_campaigns (
    id                    UUID          PRIMARY KEY,
    -- 레거시 PTNCODE(제휴사 코드) 자리. 멀티테넌트는 1차 범위 밖이라 지금은 항상 NULL 이지만,
    -- 나중에 컬럼을 추가하면 이미 쌓인 행의 소속을 되살릴 수 없어서 처음부터 심어 둔다.
    tenant_ref            VARCHAR(32),
    name                  VARCHAR(200)  NOT NULL,
    -- DAILY  = 기간 전체가 한 판 (레거시 EDATE_TYPE='D')
    -- MONTHLY= 달마다 새 판     (레거시 EDATE_TYPE='M', EDATE_MONTH)
    period_type           VARCHAR(16)   NOT NULL,
    starts_on             DATE          NOT NULL,
    ends_on               DATE          NOT NULL,
    -- CUMULATIVE  누적 N일 (레거시 'N')
    -- CONSECUTIVE 연속 N일 (레거시 'Y')
    -- EVERY_DAY   매일 지급, 목표 없음 (레거시 'C')
    streak_rule           VARCHAR(16)   NOT NULL,
    required_count        INTEGER       NOT NULL DEFAULT 0,
    -- EVERY_DAY(ED) 전일 / WEEKDAY(WD) 평일만 / WEEKEND(WE) 주말만
    day_type_rule         VARCHAR(8)    NOT NULL,
    daily_reward_points   NUMERIC(19,2) NOT NULL DEFAULT 0,
    goal_reward_points    NUMERIC(19,2) NOT NULL DEFAULT 0,
    reward_expires_from   DATE,
    reward_expires_on     DATE,
    status                VARCHAR(16)   NOT NULL,
    pc_image_url          VARCHAR(500),
    mobile_image_url      VARCHAR(500),
    message_before        TEXT,
    message_running       TEXT,
    message_achieved      TEXT,
    message_closed        TEXT,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_by            VARCHAR(100),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version               BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT attendance_campaigns_period_type_ck CHECK (period_type IN ('DAILY', 'MONTHLY')),
    CONSTRAINT attendance_campaigns_streak_rule_ck CHECK (streak_rule IN ('CUMULATIVE', 'CONSECUTIVE', 'EVERY_DAY')),
    CONSTRAINT attendance_campaigns_day_type_ck    CHECK (day_type_rule IN ('EVERY_DAY', 'WEEKDAY', 'WEEKEND')),
    CONSTRAINT attendance_campaigns_status_ck      CHECK (status IN ('DRAFT', 'RUNNING', 'CLOSED')),
    CONSTRAINT attendance_campaigns_period_ck      CHECK (ends_on >= starts_on)
);

COMMENT ON COLUMN attendance_campaigns.required_count IS
    '목표 일수. streak_rule=EVERY_DAY 면 목표가 없으므로 0.';
COMMENT ON COLUMN attendance_campaigns.reward_expires_on IS
    '보상 포인트의 소멸일. NULL 이면 무기한 로트로 적립된다(order-service 포인트 원장 계약).';

CREATE INDEX attendance_campaigns_open_idx
    ON attendance_campaigns (status, starts_on, ends_on)
    WHERE status = 'RUNNING';

-- ── 출석 기록 (레거시 TBL_ATTENDANCE_APPLY) ────────────────────────────────────────
CREATE TABLE attendance_records (
    id                      UUID          PRIMARY KEY,
    campaign_id             UUID          NOT NULL REFERENCES attendance_campaigns (id),
    -- order-service 의 userId 를 문자열로 들고 있는다. 회원 테이블은 이 서비스가 소유하지 않으므로
    -- FK 를 걸 수 없고 걸어서도 안 된다 — 참조는 이벤트 계약으로만 좁힌다.
    member_ref              VARCHAR(64)   NOT NULL,
    attended_on             DATE          NOT NULL,
    daily_reward_points     NUMERIC(19,2) NOT NULL DEFAULT 0,
    -- 참여 시점 조건 스냅샷 (레거시 EVENT_HISTORY_*). 캠페인을 나중에 고쳐도 "그때 어떤 조건으로
    -- 참여했는지" 가 남아야 문의 대응이 된다. 레거시가 이걸 남긴 판단은 그대로 가져왔다.
    campaign_name_snapshot  VARCHAR(200)  NOT NULL,
    streak_rule_snapshot    VARCHAR(16)   NOT NULL,
    period_start_snapshot   DATE          NOT NULL,
    period_end_snapshot     DATE          NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- 하루 한 번. 레거시의 select-then-insert 를 대체하는 진짜 방어선이다.
    CONSTRAINT attendance_records_daily_uk UNIQUE (campaign_id, member_ref, attended_on)
);

CREATE INDEX attendance_records_member_idx
    ON attendance_records (campaign_id, member_ref, attended_on DESC);

-- ── 목표 달성 (레거시 TBL_ATTENDANCE_SUCCESS) ──────────────────────────────────────
CREATE TABLE attendance_achievements (
    id              UUID          PRIMARY KEY,
    campaign_id     UUID          NOT NULL REFERENCES attendance_campaigns (id),
    member_ref      VARCHAR(64)   NOT NULL,
    achieved_on     DATE          NOT NULL,
    reward_points   NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- 연속 캠페인은 기간 안에서 목표를 여러 번 채울 수 있다(N일 연속을 두 번). 하루에 두 번은 없다.
    CONSTRAINT attendance_achievements_uk UNIQUE (campaign_id, member_ref, achieved_on)
);

-- ── 럭키박스 캠페인 (레거시 TBL_LUCKYBOX) ──────────────────────────────────────────
CREATE TABLE luckybox_campaigns (
    id                       UUID          PRIMARY KEY,
    tenant_ref               VARCHAR(32),
    name                     VARCHAR(200)  NOT NULL,
    starts_on                DATE          NOT NULL,
    ends_on                  DATE          NOT NULL,
    status                   VARCHAR(16)   NOT NULL,
    -- IMMEDIATE 즉시 지급(레거시 BENEFIT_TYPE=1) / BATCH 일괄 지급(=2, BENEFIT_DATE 에 몰아서)
    benefit_type             VARCHAR(16)   NOT NULL,
    benefit_on               DATE,
    -- PER_DAY 하루 한 번(레거시 EVENT_CONDITION=1) / PER_PERIOD 기간 중 한 번(=2)
    entry_condition          VARCHAR(16)   NOT NULL,
    member_joined_from       DATE,
    reward_expires_on        DATE,
    -- 참여 자격의 금액 기준. ACTUAL_PAID 실결제금액(레거시 PRICE_TYPE=1) / ORDER_TOTAL 구매금액(=2).
    -- NULL 이면 금액 조건 없음.
    amount_basis             VARCHAR(16),
    min_order_amount         NUMERIC(19,2),
    -- 금액을 언제부터 인정하는지. SHIPPING_STARTED(1) / IN_TRANSIT(2) / DELIVERED(3).
    shipping_status_required VARCHAR(16),
    note                     TEXT,
    pc_image_url             VARCHAR(500),
    mobile_image_url         VARCHAR(500),
    created_by               VARCHAR(100),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_by               VARCHAR(100),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version                  BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT luckybox_campaigns_status_ck    CHECK (status IN ('DRAFT', 'RUNNING', 'CLOSED')),
    CONSTRAINT luckybox_campaigns_benefit_ck   CHECK (benefit_type IN ('IMMEDIATE', 'BATCH')),
    CONSTRAINT luckybox_campaigns_entry_ck     CHECK (entry_condition IN ('PER_DAY', 'PER_PERIOD')),
    CONSTRAINT luckybox_campaigns_basis_ck     CHECK (amount_basis IS NULL OR amount_basis IN ('ACTUAL_PAID', 'ORDER_TOTAL')),
    CONSTRAINT luckybox_campaigns_shipping_ck  CHECK (shipping_status_required IS NULL
                                                     OR shipping_status_required IN ('SHIPPING_STARTED', 'IN_TRANSIT', 'DELIVERED')),
    CONSTRAINT luckybox_campaigns_period_ck    CHECK (ends_on >= starts_on),
    -- 일괄 지급인데 지급일이 없으면 보상이 영원히 대기한다. 화면엔 "당첨"이 뜨고 포인트만 안 들어온다.
    CONSTRAINT luckybox_campaigns_benefit_on_ck CHECK (benefit_type <> 'BATCH' OR benefit_on IS NOT NULL)
);

CREATE INDEX luckybox_campaigns_open_idx
    ON luckybox_campaigns (status, starts_on, ends_on)
    WHERE status = 'RUNNING';

-- ── 럭키박스 경품 (레거시 TBL_LUCKYBOX_ITEM) ───────────────────────────────────────
CREATE TABLE luckybox_prizes (
    id             UUID          PRIMARY KEY,
    campaign_id    UUID          NOT NULL REFERENCES luckybox_campaigns (id),
    -- POINT 포인트 지급 / TEXT 문구만(쿠폰코드·꽝 등 원장과 무관한 것)
    prize_type     VARCHAR(16)   NOT NULL,
    reward_points  NUMERIC(19,2),
    text_reward    VARCHAR(200),
    -- NULL = 무제한. 레거시는 0 을 무제한과 소진의 양쪽 의미로 썼다.
    total_quota    INTEGER,
    daily_quota    INTEGER,
    -- 가중치. 합이 1 일 필요는 없다 — 추첨은 합으로 정규화한다(레거시와 동일).
    win_rate       NUMERIC(9,6)  NOT NULL,
    issued_count   INTEGER       NOT NULL DEFAULT 0,
    -- 일일 수량 소진량. 날짜가 바뀌면 카운터를 1 로 되돌린다(예약 UPDATE 안에서 CASE 로 처리).
    -- 날짜별 행을 따로 두지 않은 이유는, 없는 행을 만들어야 하는 순간 "INSERT 해 보고 충돌하면
    -- UPDATE" 가 되는데 PostgreSQL 에서 제약 위반은 트랜잭션 전체를 중단시켜 재시도가 불가능하기
    -- 때문이다. 카운터를 경품 행에 두면 예약이 조건부 UPDATE 한 문장으로 끝난다.
    -- 날짜별 실제 지급 이력이 필요하면 luckybox_draws (prize_id, drawn_on) 를 세면 된다.
    daily_issued_count  INTEGER  NOT NULL DEFAULT 0,
    daily_issued_date   DATE,
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    display_order  INTEGER       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version        BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT luckybox_prizes_type_ck  CHECK (prize_type IN ('POINT', 'TEXT')),
    CONSTRAINT luckybox_prizes_rate_ck  CHECK (win_rate >= 0),
    CONSTRAINT luckybox_prizes_quota_ck CHECK (total_quota IS NULL OR total_quota >= 0),
    -- 포인트 경품인데 금액이 없으면 당첨자에게 0 포인트가 나간다. 지금 막는 편이 싸다.
    CONSTRAINT luckybox_prizes_point_ck CHECK (prize_type <> 'POINT' OR reward_points IS NOT NULL)
);

CREATE INDEX luckybox_prizes_campaign_idx ON luckybox_prizes (campaign_id, display_order);

-- ── 럭키박스 참여 (레거시 TBL_LUCKYBOX_APPLY) ──────────────────────────────────────
CREATE TABLE luckybox_draws (
    id             UUID          PRIMARY KEY,
    campaign_id    UUID          NOT NULL REFERENCES luckybox_campaigns (id),
    member_ref     VARCHAR(64)   NOT NULL,
    prize_id       UUID          REFERENCES luckybox_prizes (id),
    prize_type     VARCHAR(16)   NOT NULL,
    reward_points  NUMERIC(19,2),
    text_reward    VARCHAR(200),
    drawn_on       DATE          NOT NULL,
    drawn_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    -- 참여 제한을 인덱스로 강제하기 위한 슬롯 키. PER_DAY 면 'YYYY-MM-DD', PER_PERIOD 면 'ALL'.
    -- 조건에 따라 유니크 범위가 달라지는 걸 부분 인덱스로 표현하려면 캠페인 행을 봐야 하는데,
    -- 인덱스는 다른 테이블을 볼 수 없다. 슬롯을 값으로 만들어 한 인덱스로 둘 다 막는다.
    entry_slot     VARCHAR(16)   NOT NULL,

    CONSTRAINT luckybox_draws_type_ck CHECK (prize_type IN ('POINT', 'TEXT')),
    CONSTRAINT luckybox_draws_slot_uk UNIQUE (campaign_id, member_ref, entry_slot)
);

CREATE INDEX luckybox_draws_member_idx ON luckybox_draws (campaign_id, member_ref, drawn_at DESC);

-- 경품별·날짜별 지급 이력 — 일일 수량 카운터가 아니라 이쪽이 정산·감사의 근거다.
CREATE INDEX luckybox_draws_prize_day_idx ON luckybox_draws (prize_id, drawn_on);

-- ── 보상 요청 ─────────────────────────────────────────────────────────────────────
-- 이 서비스가 포인트에 대해 아는 전부다. 잔액도 로트도 여기 없다 — "얼마를 누구에게 달라고
-- 요청했고, 그게 실제로 적립됐는지" 만 있다. 적립 여부는 lemuel.point.granted 를 받아 갱신한다.
CREATE TABLE reward_grants (
    id              UUID          PRIMARY KEY,
    -- ATTENDANCE_DAILY 일일 출석 / ATTENDANCE_GOAL 목표 달성 / LUCKYBOX 추첨 당첨
    source          VARCHAR(24)   NOT NULL,
    -- 위 세 종류의 원본 행 id (attendance_records / attendance_achievements / luckybox_draws)
    reference_id    UUID          NOT NULL,
    campaign_id     UUID          NOT NULL,
    member_ref      VARCHAR(64)   NOT NULL,
    amount          NUMERIC(19,2) NOT NULL,
    expires_on      DATE,
    memo            VARCHAR(300),
    status          VARCHAR(16)   NOT NULL,
    -- BATCH 캠페인의 지급 예정일. NULL 이면 즉시 요청 대상이다.
    scheduled_on    DATE,
    requested_at    TIMESTAMPTZ,
    confirmed_at    TIMESTAMPTZ,
    failure_reason  VARCHAR(500),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version         BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT reward_grants_source_ck CHECK (source IN ('ATTENDANCE_DAILY', 'ATTENDANCE_GOAL', 'LUCKYBOX')),
    CONSTRAINT reward_grants_status_ck CHECK (status IN ('PENDING', 'REQUESTED', 'CONFIRMED', 'FAILED')),
    CONSTRAINT reward_grants_amount_ck CHECK (amount > 0),
    -- 원본 한 건에 보상 한 건. 재시도가 두 번 적립하는 경로를 구조적으로 없앤다.
    CONSTRAINT reward_grants_reference_uk UNIQUE (source, reference_id)
);

COMMENT ON COLUMN reward_grants.id IS
    'order-service 포인트 적립의 멱등 키(referenceId)로 그대로 실려 나간다 — referenceType 은 source.';

CREATE INDEX reward_grants_pending_idx
    ON reward_grants (status, scheduled_on)
    WHERE status = 'PENDING';

CREATE INDEX reward_grants_member_idx ON reward_grants (member_ref, created_at DESC);
