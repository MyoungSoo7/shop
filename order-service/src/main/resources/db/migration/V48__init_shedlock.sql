-- ShedLock — @Scheduled 분산 락 테이블.
-- replicas N 개 중 1 개만 실행 보장. 현재 prod 는 replicas: 1 이라 미래 HA 대비.
CREATE TABLE opslab.shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);

COMMENT ON TABLE opslab.shedlock IS 'ShedLock distributed lock — @SchedulerLock annotation 의 backing store';
