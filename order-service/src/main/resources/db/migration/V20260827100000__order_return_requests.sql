-- 반품·교환·취소 신청 레코드.
--
-- 지금까지 "신청"은 주문 상태 하나(CANCELLATION_REQUESTED / REFUND_REQUESTED)와 상태 이력의
-- reason 문자열로만 남았다. 그래서 신청에 딸린 사실들이 갈 곳이 없었다:
--   ① 교환이라는 종류 자체가 없다 — 고객이 "같은 걸로 바꿔 주세요"를 표현할 방법이 없어
--      환불 신청으로 받고 운영자가 메모로 구분해 왔다.
--   ② 환불받을 계좌가 없다 — 무통장·가상계좌(TenderType.awaitsDeposit)는 PG 로 되돌릴 통로가
--      없어 사람이 계좌로 보내야 하는데, 그 계좌를 받는 자리가 어디에도 없었다.
--   ③ 회수 송장이 없다 — 고객이 물건을 어느 택배로 언제 보냈는지 기록이 없어, 도착 확인은
--      전화와 눈대중이었다.
--
-- 세 가지를 한 테이블에 두는 건 이 셋이 한 사건의 서로 다른 면이기 때문이다(회수 송장은 반품에만,
-- 재배송 송장은 교환에만 붙지만 둘 다 "그 신청"의 진행이다). 주문 상태는 여전히 흐름의 축이고,
-- 이 표는 그 흐름에 붙은 사실을 담는다 — 상태를 이 표에서 다시 계산하지 않는다.
--
-- refund_* 3 칸은 함께 채워지거나 함께 비어야 한다(반쪽짜리 계좌로는 송금할 수 없다).

CREATE TABLE IF NOT EXISTS order_return_requests (
    id                   BIGSERIAL PRIMARY KEY,
    order_id             BIGINT       NOT NULL,
    user_id              BIGINT       NOT NULL,

    request_type         VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,

    reason_code          VARCHAR(40)  NOT NULL,
    reason_detail        VARCHAR(500),

    -- 환불 수취 계좌 — PG 로 되돌릴 수 없는 결제(무통장·가상계좌)에서만 필수.
    -- 접수 시점에 결제 슬라이스가 내린 판정. 파생 가능한 값이지만 여기 박아 둔다 — 대기열이
    -- 신청 100 건마다 결제를 다시 조회하지 않게, 그리고 접수 후 결제가 바뀌어도 고객이 계좌를 낸
    -- 근거와 판정이 어긋나지 않게.
    refund_account_required BOOLEAN      NOT NULL DEFAULT FALSE,
    refund_bank_code     VARCHAR(20),
    refund_account_no    VARCHAR(60),
    refund_account_holder VARCHAR(60),

    -- 고객 → 판매자 회수 송장.
    return_carrier       VARCHAR(40),
    return_tracking_no   VARCHAR(60),

    -- 판매자 → 고객 교환 재배송 송장(교환에만).
    exchange_carrier     VARCHAR(40),
    exchange_tracking_no VARCHAR(60),

    requested_by         VARCHAR(255) NOT NULL,
    processed_by         VARCHAR(255),
    reject_reason        VARCHAR(500),

    requested_at         TIMESTAMP    NOT NULL,
    approved_at          TIMESTAMP,
    collected_at         TIMESTAMP,
    exchange_shipped_at  TIMESTAMP,
    completed_at         TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL,

    CONSTRAINT ck_order_return_requests_type
        CHECK (request_type IN ('RETURN', 'EXCHANGE', 'CANCEL')),
    CONSTRAINT ck_order_return_requests_status
        CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'COLLECTED', 'COMPLETED', 'WITHDRAWN')),
    -- 계좌 3 칸은 전부 채우거나 전부 비운다.
    CONSTRAINT ck_order_return_requests_refund_account
        CHECK ((refund_bank_code IS NULL AND refund_account_no IS NULL AND refund_account_holder IS NULL)
            OR (refund_bank_code IS NOT NULL AND refund_account_no IS NOT NULL AND refund_account_holder IS NOT NULL))
);

-- 한 주문에 진행 중인 신청은 하나뿐이다. 종단(REJECTED·COMPLETED·WITHDRAWN)은 여러 건 남을 수 있다
-- (반품이 거절된 뒤 교환으로 다시 신청하는 흐름이 정상이다).
CREATE UNIQUE INDEX IF NOT EXISTS ux_order_return_requests_open
    ON order_return_requests (order_id)
    WHERE status IN ('REQUESTED', 'APPROVED', 'COLLECTED');

CREATE INDEX IF NOT EXISTS ix_order_return_requests_order    ON order_return_requests (order_id);
CREATE INDEX IF NOT EXISTS ix_order_return_requests_user     ON order_return_requests (user_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS ix_order_return_requests_queue    ON order_return_requests (status, requested_at);

COMMENT ON TABLE order_return_requests IS
    '반품·교환·취소 신청 — 사유, 환불 수취 계좌, 회수·재배송 송장을 담는다. 주문 상태의 사본이 아니다.';
COMMENT ON COLUMN order_return_requests.refund_account_no IS
    'PG 로 되돌릴 수 없는 결제(무통장·가상계좌)의 환불 수취 계좌. PG 결제면 NULL 이 정상이다.';
COMMENT ON COLUMN order_return_requests.return_tracking_no IS
    '고객이 물건을 돌려보낸 송장 번호. 회수 확인(COLLECTED)의 근거.';
