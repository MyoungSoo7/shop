-- V4: 부분환불 리팩토링 - Refund 테이블 및 정산 조정 추가
-- 목표: Payment 음수 레코드 방식을 Refund 엔티티로 분리하고, 정산 확정 후 환불을 조정(Adjustment)으로 처리

-- 1. payments 테이블에 refunded_amount 컬럼 추가
ALTER TABLE payments ADD COLUMN IF NOT EXISTS refunded_amount DECIMAL(10, 2) NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS captured_at TIMESTAMP;

-- refunded_amount는 환불 누적 합계 (0 이상, amount 이하)
ALTER TABLE payments ADD CONSTRAINT chk_payments_refunded_amount
CHECK (refunded_amount >= 0 AND refunded_amount <= amount);

-- 인덱스: 환불 관련 조회 최적화
CREATE INDEX IF NOT EXISTS idx_payments_status_refunded_amount
ON payments(status, refunded_amount);

-- 2. refunds 테이블 생성 (환불 이력)
CREATE TABLE refunds (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    reason TEXT,
    idempotency_key VARCHAR(255) NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT chk_refunds_amount CHECK (amount > 0)
);

-- 멱등성 보장: 동일 payment에 동일 idempotency_key로 중복 환불 방지
CREATE UNIQUE INDEX idx_refunds_payment_idempotency
ON refunds(payment_id, idempotency_key);

-- 조회 최적화 인덱스
CREATE INDEX idx_refunds_payment_id ON refunds(payment_id);
CREATE INDEX idx_refunds_status ON refunds(status);
CREATE INDEX idx_refunds_requested_at ON refunds(requested_at);

-- 3. settlement_adjustments 테이블 생성 (정산 조정)
CREATE TABLE settlement_adjustments (
    id BIGSERIAL PRIMARY KEY,
    settlement_id BIGINT NOT NULL,
    refund_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    adjustment_date DATE NOT NULL,
    confirmed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_adjustment_settlement FOREIGN KEY (settlement_id) REFERENCES settlements(id),
    CONSTRAINT fk_adjustment_refund FOREIGN KEY (refund_id) REFERENCES refunds(id),
    CONSTRAINT chk_adjustments_amount CHECK (amount < 0)
);

-- 환불 1건당 조정 1건 보장
CREATE UNIQUE INDEX idx_adjustments_refund_id_unique
ON settlement_adjustments(refund_id);

-- 조회 최적화 인덱스
CREATE INDEX idx_adjustments_settlement_id ON settlement_adjustments(settlement_id);
CREATE INDEX idx_adjustments_status ON settlement_adjustments(status);
CREATE INDEX idx_adjustments_date_status ON settlement_adjustments(adjustment_date, status);

-- 4. 기존 데이터 마이그레이션 (음수 Payment 레코드가 있다면 정리)
-- 실제 운영 데이터가 있다면 별도 마이그레이션 스크립트 필요
-- 현재는 개발 단계로 가정, 음수 금액 payment는 수동 처리 필요

-- COMMENT 추가 (선택사항)
COMMENT ON TABLE refunds IS '환불 이력 테이블 - 부분환불/전체환불 추적';
COMMENT ON TABLE settlement_adjustments IS '정산 조정 테이블 - CONFIRMED 정산에 대한 환불 처리';
COMMENT ON COLUMN refunds.idempotency_key IS '멱등성 보장을 위한 클라이언트 제공 키';
COMMENT ON COLUMN settlement_adjustments.amount IS '조정 금액 (항상 음수)';
