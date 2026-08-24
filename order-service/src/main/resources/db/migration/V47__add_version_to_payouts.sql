-- V47: payouts 낙관적 락(optimistic lock) 컬럼 추가
--
-- 상태 전이(REQUESTED → SENDING → COMPLETED/FAILED, retry/cancel)가 동시에 일어날 때
-- lost update 를 방지한다. JPA @Version 이 UPDATE ... WHERE version = ? 로 충돌을 감지하여
-- 두 번째 트랜잭션에 OptimisticLockException 을 던진다 — 중복 송금/상태 꼬임 차단.

ALTER TABLE opslab.payouts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN opslab.payouts.version IS '@Version 낙관적 락 — 동시 상태 전이 시 lost update 방지';
