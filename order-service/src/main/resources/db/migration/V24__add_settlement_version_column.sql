-- 정산 동시성 제어를 위한 낙관적 락 버전 컬럼 추가
-- 이유: 동일 정산에 대한 환불 조정이 동시에 발생할 때 lost update 방지

ALTER TABLE opslab.settlements
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN opslab.settlements.version IS '낙관적 락 버전 (JPA @Version)';
