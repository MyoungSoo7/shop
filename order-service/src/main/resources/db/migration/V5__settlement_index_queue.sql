-- Elasticsearch 색인 재시도 큐 테이블
CREATE TABLE settlement_index_queue (
    id BIGSERIAL PRIMARY KEY,
    settlement_id BIGINT NOT NULL,
    operation VARCHAR(20) NOT NULL CHECK (operation IN ('INDEX', 'UPDATE', 'DELETE')),
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED')),
    error_message TEXT,
    next_retry_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP
);

-- 인덱스 생성
CREATE INDEX idx_settlement_index_queue_status ON settlement_index_queue(status);
CREATE INDEX idx_settlement_index_queue_settlement_id ON settlement_index_queue(settlement_id);
CREATE INDEX idx_settlement_index_queue_next_retry ON settlement_index_queue(next_retry_at) WHERE status = 'PENDING' OR status = 'FAILED';

-- 코멘트
COMMENT ON TABLE settlement_index_queue IS 'Elasticsearch 색인 작업 재시도 큐';
COMMENT ON COLUMN settlement_index_queue.settlement_id IS '정산 ID';
COMMENT ON COLUMN settlement_index_queue.operation IS '작업 유형: INDEX(신규), UPDATE(수정), DELETE(삭제)';
COMMENT ON COLUMN settlement_index_queue.retry_count IS '재시도 횟수';
COMMENT ON COLUMN settlement_index_queue.max_retries IS '최대 재시도 횟수';
COMMENT ON COLUMN settlement_index_queue.status IS '상태: PENDING(대기), PROCESSING(처리중), SUCCESS(성공), FAILED(실패)';
COMMENT ON COLUMN settlement_index_queue.next_retry_at IS '다음 재시도 시각 (지수 백오프 적용)';
