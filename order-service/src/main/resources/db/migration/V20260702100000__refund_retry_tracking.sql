-- 환불 실패 자동 재시도/복구를 위한 상태 추적 컬럼.
-- 배경: PG 환불 실패 시 이전에는 트랜잭션이 통째로 롤백돼 REQUESTED 행까지 사라져
--       "실패 이력"이 남지 않았다. 이제 REQUESTED 행을 락 획득 전에 독립 커밋하고,
--       실패는 별도 트랜잭션 UPDATE 로 FAILED 마킹해 이력을 보존한다.
--       스케줄러(RefundRetryScheduler)가 next_retry_at 이 도래한 FAILED 환불을
--       멱등 키로 재호출한다. retry_count 가 상한(도메인 Refund.MAX_RETRIES)에 도달하면
--       next_retry_at 을 NULL 로 두어 자동 재시도를 멈추고 관리자 개입 대상으로 남긴다.

ALTER TABLE refunds ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE refunds ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP;

COMMENT ON COLUMN refunds.retry_count IS 'PG 환불 실패 재시도 횟수(0=최초 시도). 상한 도달 시 자동 재시도 중단';
COMMENT ON COLUMN refunds.next_retry_at IS '다음 자동 재시도 예정 시각. NULL=재시도 대상 아님(성공/소진/진행중)';

-- 스케줄러 조회 최적화: FAILED 이면서 재시도 시각이 도래한 행만 스캔.
CREATE INDEX IF NOT EXISTS idx_refunds_retry_due
ON refunds(next_retry_at)
WHERE status = 'FAILED' AND next_retry_at IS NOT NULL;
