-- V49: Ledger 전용 트랜잭셔널 아웃박스.
--
-- 정산 확정/환불조정 트랜잭션과 *같은 커밋* 안에 원장 작업(분개/역분개) 의도를 row 로 남긴다.
-- 기존 @TransactionalEventListener(AFTER_COMMIT)+@Async 경로는 commit 후 비동기 실행 전
-- 프로세스가 죽으면 작업이 유실됐다 — 아웃박스는 같은 트랜잭션에 기록되어 크래시에도 살아남고,
-- settlement-service 로컬 폴러가 PENDING row 를 읽어 멱등 use case 를 직접 호출한다 (Kafka 미경유).
--
-- 제네릭 outbox_events(결제→Kafka 라우팅)와 분리한 이유: 그 폴러는 prod 에서 Kafka 로 발행하므로
-- 로컬 원장 작업에 부적합. 본 테이블은 settlement-service 안에서만 소비된다.
--
-- task_type 별 사용 컬럼:
--   CREATE_ENTRY  : settlement_id 만 사용 (createFromSettlement)
--   REVERSE_ENTRY : settlement_id + refund_id + refund_amount + adjustment_date (reverseForRefund)
--
-- 멱등: 대상 use case 가 existsByReference 로 중복을 거르므로 at-least-once 재시도가 안전하다.

CREATE TABLE IF NOT EXISTS opslab.ledger_outbox (
    id              BIGSERIAL PRIMARY KEY,
    task_type       VARCHAR(30)    NOT NULL,           -- CREATE_ENTRY | REVERSE_ENTRY
    settlement_id   BIGINT         NOT NULL,           -- 대상 settlement.id
    refund_id       BIGINT,                            -- REVERSE_ENTRY 전용
    refund_amount   DECIMAL(14, 2),                    -- REVERSE_ENTRY 전용
    adjustment_date DATE,                              -- REVERSE_ENTRY 전용
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',  -- PENDING | DONE | FAILED
    retry_count     INT            NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMP,

    CONSTRAINT chk_ledger_outbox_task_type CHECK (task_type IN ('CREATE_ENTRY', 'REVERSE_ENTRY')),
    CONSTRAINT chk_ledger_outbox_status    CHECK (status IN ('PENDING', 'DONE', 'FAILED'))
);

-- 폴러는 PENDING 을 id 오름차순으로 읽는다 — (status, id) 부분탐색에 최적.
CREATE INDEX IF NOT EXISTS idx_ledger_outbox_poll ON opslab.ledger_outbox (status, id);

COMMENT ON TABLE  opslab.ledger_outbox IS '원장 전용 트랜잭셔널 아웃박스. 정산/환불 트랜잭션과 같은 커밋에 원장 작업을 기록, 로컬 폴러가 멱등 처리.';
COMMENT ON COLUMN opslab.ledger_outbox.task_type   IS 'CREATE_ENTRY(정산 확정 분개) | REVERSE_ENTRY(환불 역분개)';
COMMENT ON COLUMN opslab.ledger_outbox.status      IS 'PENDING(미처리) → DONE(완료) | FAILED(재시도 한도 초과)';
COMMENT ON COLUMN opslab.ledger_outbox.retry_count IS '실패 누적 횟수. 한도 도달 시 status=FAILED.';
