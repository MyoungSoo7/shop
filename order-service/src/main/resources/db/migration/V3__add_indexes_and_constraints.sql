-- 제약조건: order_id는 하나의 활성 결제만 가능 (1:1 관계)
CREATE UNIQUE INDEX IF NOT EXISTS idx_payments_order_id_unique 
ON payments(order_id) 
WHERE status IN ('READY', 'AUTHORIZED', 'CAPTURED');

-- 제약조건: payment_id는 unique (하나의 결제에 하나의 정산)
CREATE UNIQUE INDEX IF NOT EXISTS idx_settlements_payment_id_unique 
ON settlements(payment_id);

-- 배치 작업용 복합 인덱스
CREATE INDEX IF NOT EXISTS idx_payments_status_updated_at 
ON payments(status, updated_at);

CREATE INDEX IF NOT EXISTS idx_settlements_date_status 
ON settlements(settlement_date, status);

-- 배치 작업 실행 이력 테이블
CREATE TABLE IF NOT EXISTS batch_run_history (
    id BIGSERIAL PRIMARY KEY,
    batch_name VARCHAR(100) NOT NULL,
    run_id VARCHAR(100) NOT NULL,
    target_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    processed_count INT DEFAULT 0,
    error_message TEXT
);

-- 인덱스: 배치 실행 이력 조회
CREATE INDEX IF NOT EXISTS idx_batch_history_run_id ON batch_run_history(run_id);
CREATE INDEX IF NOT EXISTS idx_batch_history_batch_name ON batch_run_history(batch_name);
CREATE INDEX IF NOT EXISTS idx_batch_history_target_date ON batch_run_history(target_date);
CREATE INDEX IF NOT EXISTS idx_batch_history_status ON batch_run_history(status);
