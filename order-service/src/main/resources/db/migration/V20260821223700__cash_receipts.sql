-- 현금영수증.
--
-- 레거시 커머스(ssgb2e-front `OrderCashReceiptServiceImpl`)는 급여공제·무통장 결제 건에 대해
-- PG 모듈로 발급/취소 전문을 태우고 그 요청·응답을 자체 테이블에 남겼다. Lemuel 에는 이 축이
-- 통째로 없어서, 계좌이체·가상계좌로 받은 돈이 **어디에도 신고되지 않았다** — 개인은 소득공제를,
-- 사업자는 매입세액공제를 그대로 잃는다.
--
-- 카드 결제는 대상이 아니다: 카드 매출은 카드사 매출전표로 이미 신고되어, 현금영수증까지 붙이면
-- 같은 거래가 두 번 잡히는 이중 공제가 된다.

CREATE TABLE IF NOT EXISTS cash_receipts (
    id               BIGSERIAL PRIMARY KEY,
    payment_id       BIGINT      NOT NULL REFERENCES payments(id),
    order_id         BIGINT      NOT NULL REFERENCES orders(id),
    user_id          BIGINT      REFERENCES users(id),
    purpose          VARCHAR(20) NOT NULL CHECK (purpose IN ('INCOME_DEDUCTION', 'EXPENSE_PROOF')),
    identifier_type  VARCHAR(20) NOT NULL CHECK (identifier_type IN ('MOBILE', 'CASH_RECEIPT_CARD', 'BUSINESS_NUMBER')),
    -- 숫자만 정규화해 보관한다. "010-1234-5678" 과 "01012345678" 이 갈라지면 중복 판정이 무너진다.
    identifier_value VARCHAR(32) NOT NULL,
    total_amount     NUMERIC(19, 2) NOT NULL CHECK (total_amount > 0),
    -- 공급가액·부가세는 발급 시점에 확정해 보관한다. 표시할 때마다 다시 계산하면 세율이 바뀌는 순간
    -- 과거 영수증의 숫자가 소급해 달라진다 — 세금 서류에서는 그것이 곧 위조다.
    supply_amount    NUMERIC(19, 2) NOT NULL,
    vat_amount       NUMERIC(19, 2) NOT NULL,
    status           VARCHAR(20) NOT NULL
                     CHECK (status IN ('REQUESTED', 'ISSUED', 'CANCEL_REQUESTED', 'CANCELED', 'FAILED')),
    approval_number  VARCHAR(40),
    failure_reason   VARCHAR(300),
    issued_at        TIMESTAMP,
    canceled_at      TIMESTAMP,
    cancel_reason    VARCHAR(300),
    requested_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP   NOT NULL DEFAULT NOW(),
    -- 구성적 정합: 공급가액 + 부가세 = 총액. 분해 로직이 어긋나면 INSERT 가 그 자리에서 막힌다.
    CONSTRAINT ck_cash_receipt_amount_split CHECK (supply_amount + vat_amount = total_amount)
);

-- 결제 1건당 유효 1건. 실패·취소 건은 자리를 비운다 — 한 번 실패했다고 재발급이 영영 막히면
-- 고객이 세금 혜택을 잃는다. 반대로 REQUESTED 를 빼면 응답 대기 중 재신청이 이중 발급이 된다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cash_receipt_active_payment
    ON cash_receipts (payment_id)
    WHERE status IN ('REQUESTED', 'ISSUED', 'CANCEL_REQUESTED');

CREATE INDEX IF NOT EXISTS idx_cash_receipts_order ON cash_receipts (order_id);
CREATE INDEX IF NOT EXISTS idx_cash_receipts_user ON cash_receipts (user_id);

COMMENT ON TABLE cash_receipts IS '현금영수증(계좌이체·가상계좌 결제 전용). 카드 결제는 카드사 전표로 이미 신고되어 대상 아님';
COMMENT ON COLUMN cash_receipts.identifier_value IS '정규화된 숫자열(휴대폰/현금영수증카드/사업자번호). 응답에는 마스킹된 값만 노출';
