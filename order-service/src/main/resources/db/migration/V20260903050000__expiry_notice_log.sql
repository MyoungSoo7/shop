-- 만료 예고 통보 원장.
--
-- 소멸 배치는 셋(포인트 로트 · 기프트카드 · 선물 수령권) 다 있는데 사전 통보가 하나도 없었다.
-- 레거시(ofDentis)는 소멸 잡과 통보 잡이 짝으로 있었다 — ExtinctionMileageJob↔NotiExpiredMileageJob,
-- ExtinctionCouponJob↔NotiExpiredCouponJob. 소멸만 옮기고 통보를 안 옮긴 상태였고,
-- 그동안 사용자는 예고 없이 돈을 잃었다.
--
-- 왜 대상 테이블에 notified_at 칸을 안 붙이고 별도 원장을 만드는가:
--
--   1. point_lots · gift_cards 는 @Version 낙관락이 걸린 금전 애그리것이다. 통보 배치가 여기에
--      UPDATE 를 치면 결제·사용 트랜잭션과 버전 충돌을 일으킨다. 통보는 돈을 건드리지 않는데
--      돈의 잠금 경합을 유발할 이유가 없다.
--   2. 중복 통보 방지를 "플래그를 세팅하는 걸 잊지 않는 것"에 맡기면 언젠가 잊는다. 여기서는
--      UNIQUE 가 막는다 — 두 번째 INSERT 가 실패하는 것이 곧 멱등이다.
--   3. 단계가 늘어난다(D-30 → D-7 → D-1). 칸으로는 마지막 한 번만 기억하지만 원장은 단계별로 남는다.
--
-- 이 표는 "보냈다" 의 기록이지 발송 큐가 아니다. 실제 발송은 outbox 이벤트를 받는 쪽이 한다.
CREATE TABLE IF NOT EXISTS expiry_notice_log (
    id           BIGSERIAL    PRIMARY KEY,

    -- 무엇이 만료되는가. 애그리것마다 표가 달라 FK 를 걸 수 없다 — 그래서 (타입, 식별자) 쌍이다.
    subject_type VARCHAR(20)  NOT NULL,
    subject_id   BIGINT       NOT NULL,

    -- 통보 단계. 같은 대상에 D-30 과 D-7 을 각각 한 번씩 보낼 수 있어야 한다.
    stage        VARCHAR(20)  NOT NULL,

    -- 받는 사람. 선물 수령권처럼 회원이 아닌 대상이 있어 user_id 가 NULL 일 수 있다
    -- (그 경우 보내는 사람에게 알리고, 수령자에게는 발송 채널이 전화번호로 나간다).
    user_id      BIGINT,

    -- 통보 시점에 남아 있던 금액과 만료 예정 시각. 나중에 "얼마를 예고했는가" 를 되짚기 위한 스냅샷이다.
    amount       NUMERIC(19,2),
    expires_at   TIMESTAMPTZ  NOT NULL,

    notified_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- 멱등의 정본. 배치가 매일 돌아도 같은 (대상, 단계) 는 한 번만 들어간다.
    -- 이게 곧 "어제 보낸 걸 오늘 또 보내지 않는다" 의 강제 수단이다.
    CONSTRAINT uq_expiry_notice_subject_stage UNIQUE (subject_type, subject_id, stage),
    CONSTRAINT chk_expiry_notice_subject_type
        CHECK (subject_type IN ('POINT_LOT', 'GIFT_CARD', 'GIFT_CLAIM'))
);

-- 조회 축은 "누가 무엇을 언제 통보받았나" 다(CS 가 "안 받았다" 는 문의를 받았을 때).
CREATE INDEX IF NOT EXISTS idx_expiry_notice_user_notified
    ON expiry_notice_log(user_id, notified_at DESC);
