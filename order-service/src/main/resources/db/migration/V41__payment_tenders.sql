-- V41: 분할결제(Split Payment) — PaymentTender 1:N
--
-- 한 결제(payments) 에 여러 지불수단을 동시 사용. 예) 50,000원 = 포인트 5,000 + 상품권 10,000 + 카드 35,000.
-- 모든 tender 의 amount 합계는 payment.amount 와 정확히 일치 (도메인 불변식).
-- 환불은 역순 (마지막 결제부터) — 카드 먼저 환불 후 포인트 복원이 운영 사고를 줄인다.
--
-- POINT / GIFT_CARD 같은 내부 잔액 차감 tender 는 pg_transaction_id 가 NULL.
-- CARD / KAKAO_PAY / NAVER_PAY 등 외부 PG 호출 tender 만 pg_transaction_id 채워진다.

CREATE TABLE IF NOT EXISTS opslab.payment_tenders (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT       NOT NULL,
    tender_type         VARCHAR(30)  NOT NULL,                  -- CARD / POINT / GIFT_CARD / KAKAO_PAY / NAVER_PAY / BANK_TRANSFER
    amount              NUMERIC(12, 2) NOT NULL,
    refunded_amount     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    pg_transaction_id   VARCHAR(500),                            -- 내부 잔액 차감(POINT/GIFT_CARD)은 NULL
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING/AUTHORIZED/CAPTURED/REFUNDED/FAILED
    sequence            INTEGER      NOT NULL,                   -- 결제 시도 순서 (1, 2, 3...) — 환불 역순 처리용
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_payment_tenders_payment
        FOREIGN KEY (payment_id) REFERENCES opslab.payments(id) ON DELETE CASCADE,
    CONSTRAINT chk_payment_tenders_amount
        CHECK (amount > 0),
    CONSTRAINT chk_payment_tenders_refunded
        CHECK (refunded_amount >= 0 AND refunded_amount <= amount),
    CONSTRAINT chk_payment_tenders_status
        CHECK (status IN ('PENDING', 'AUTHORIZED', 'CAPTURED', 'REFUNDED', 'FAILED')),
    CONSTRAINT chk_payment_tenders_type
        CHECK (tender_type IN ('CARD', 'POINT', 'GIFT_CARD', 'KAKAO_PAY', 'NAVER_PAY',
                                'BANK_TRANSFER', 'VIRTUAL_ACCOUNT', 'PAYCO', 'SAMSUNG_PAY'))
);

CREATE INDEX IF NOT EXISTS idx_payment_tenders_payment
    ON opslab.payment_tenders (payment_id, sequence);

-- 같은 결제 안에서 sequence 는 유니크
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_tenders_payment_sequence
    ON opslab.payment_tenders (payment_id, sequence);

COMMENT ON TABLE opslab.payment_tenders IS
    '분할결제 라인. 한 payment 의 여러 지불수단. SUM(amount) = payments.amount (도메인 불변식).';
COMMENT ON COLUMN opslab.payment_tenders.sequence IS
    '결제 시도 순서. 환불 시 역순으로 처리 — 카드 먼저 환불 후 포인트 복원.';
COMMENT ON COLUMN opslab.payment_tenders.pg_transaction_id IS
    'CARD/KAKAO_PAY 등 외부 PG 거래만 채워짐. POINT/GIFT_CARD 는 내부 잔액 차감이라 NULL.';
