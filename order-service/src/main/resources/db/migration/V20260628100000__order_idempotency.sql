-- 주문 중복 제출 방지 — Idempotency-Key 멱등 백스톱.
-- 분산 락(Redis/InMemory)이 동시 중복 제출을 직렬화하고, 이 테이블의 PK(UNIQUE)가
-- 락이 비활성/만료된 경우에도 동일 키의 두 번째 주문 트랜잭션을 롤백시켜 최종 1건만 남긴다.
CREATE TABLE IF NOT EXISTS opslab.order_idempotency (
    idempotency_key VARCHAR(255) NOT NULL,
    order_id        BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT pk_order_idempotency PRIMARY KEY (idempotency_key)
);
