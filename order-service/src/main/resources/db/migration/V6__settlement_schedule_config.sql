-- 정산 스케줄 동적 설정 테이블
CREATE TABLE settlement_schedule_config (
    id BIGSERIAL PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL UNIQUE,
    cron_expression VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500),
    merchant_id BIGINT, -- null이면 전체 적용
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 인덱스
CREATE INDEX idx_settlement_schedule_enabled ON settlement_schedule_config(enabled);
CREATE INDEX idx_settlement_schedule_merchant ON settlement_schedule_config(merchant_id);

-- 기본 스케줄 데이터 삽입
INSERT INTO settlement_schedule_config (config_key, cron_expression, enabled, description) VALUES
('SETTLEMENT_CREATE', '0 0 2 * * *', TRUE, '매일 새벽 2시: 전날 CAPTURED 결제 → PENDING 정산 생성'),
('SETTLEMENT_CONFIRM', '0 0 3 * * *', TRUE, '매일 새벽 3시: PENDING 정산 → CONFIRMED 확정'),
('ADJUSTMENT_CONFIRM', '0 10 3 * * *', TRUE, '매일 새벽 3시 10분: PENDING 정산 조정 → CONFIRMED 확정');

-- 코멘트
COMMENT ON TABLE settlement_schedule_config IS '정산 배치 스케줄 동적 설정';
COMMENT ON COLUMN settlement_schedule_config.config_key IS '설정 키 (SETTLEMENT_CREATE, SETTLEMENT_CONFIRM, ADJUSTMENT_CONFIRM)';
COMMENT ON COLUMN settlement_schedule_config.cron_expression IS 'Cron 표현식 (예: 0 0 2 * * *)';
COMMENT ON COLUMN settlement_schedule_config.enabled IS '스케줄 활성화 여부';
COMMENT ON COLUMN settlement_schedule_config.merchant_id IS '업체 ID (null이면 전체 적용)';
