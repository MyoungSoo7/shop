-- PG 승인(Toss confirm) 멱등 백스톱 — Idempotency-Key → payment_id 매핑.
--
-- [왜] 결제 승인은 POST 인데 멱등 키가 없었다. 네트워크 재시도·더블클릭이 같은 paymentKey 로
--   confirm 을 두 번 부르면, 방어선은 하류의 uq_payments_pg_txn / idx_payments_order_id_unique
--   뿐이라 이중 결제행은 막히되 사용자에게는 500 이 나갔다. 이 테이블은 "두 번째 요청은 첫 번째
--   결과를 그대로 돌려준다"(replay)를 가능하게 해, 방어를 오류가 아니라 정상 응답으로 바꾼다.
--
-- [키] 클라이언트가 Idempotency-Key 헤더를 주면 그 값, 없으면 결제창이 발급한 paymentKey 를
--   키로 쓴다. paymentKey 는 그 승인 시도를 유일하게 가리키므로, 헤더를 보내지 않는 기존
--   클라이언트도 자동으로 보호된다.
--
-- [경계] PK(UNIQUE)가 백스톱이다 — 동시 중복 요청이 둘 다 조회를 통과해도 두 번째 INSERT 가
--   제약 위반으로 트랜잭션을 롤백시켜 최종 1건만 남는다(order_idempotency 와 동일한 구조).
CREATE TABLE IF NOT EXISTS opslab.payment_idempotency (
    idempotency_key VARCHAR(255) NOT NULL,
    payment_id      BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT pk_payment_idempotency PRIMARY KEY (idempotency_key)
);

COMMENT ON TABLE opslab.payment_idempotency IS
    'PG 승인 멱등 매핑 — 동일 Idempotency-Key(미지정 시 paymentKey) 재요청을 최초 결제로 replay.';
