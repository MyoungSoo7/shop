-- 회원 간 포인트 선물 (slice 8 — ssg_front 포인트 선물 이식)
--
-- 이 표에 상태 칼럼이 없는 것이 설계의 핵심이다. 레거시는 선물 행을 st='0' 으로 먼저 넣고
-- 포인트 이동이 끝나면 st='1' 로 올리는 2단계였다. 그 사이에서 끊기면 "포인트는 갔는데 st='0'",
-- 반대로 이동이 실패해도 남는 행이 생겼고, 나중에 이 표를 읽는 사람은 무엇이 진짜 일어난 일인지
-- 알 수 없었다. 여기서는 행 삽입과 양쪽 원장 기입이 한 트랜잭션이라 중간 상태가 존재할 수 없다.
-- 행이 있다 == 양쪽 원장이 다 적혔다.

-- 선물 번호 채번. 레거시의 SELECT NVL(MAX(IDX),0)+1 을 대체한다 — 읽고-더하고-쓰는 채번은
-- 동시 요청에서 같은 값을 두 번 준다. 시퀀스는 트랜잭션 밖에서 원자적으로 증가하므로,
-- 롤백된 요청의 번호는 되돌아오지 않는다(구멍은 생기지만 겹치지는 않는다). 겹치지 않는 쪽이
-- 훨씬 중요하다 — 같은 번호 두 건은 원장의 reference_id 를 충돌시킨다.
CREATE SEQUENCE IF NOT EXISTS point_transfer_no_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE point_transfers (
    id                BIGSERIAL     PRIMARY KEY,
    transfer_no       VARCHAR(40)   NOT NULL,
    -- 화면이 만드는 멱등 키. 보내는 이별로 유일하다 — 전역 유일로 두면 남이 우연히(또는 일부러)
    -- 같은 값을 만들었을 때 내 요청이 남의 선물로 단축 반환된다.
    request_id        VARCHAR(64)   NOT NULL,
    -- point_accounts.user_id 와 같이 FK 를 걸지 않는다. 포인트 원장은 회원 표의 수명과
    -- 분리돼 있어야 한다 — 탈퇴로 회원 행이 사라져도 주고받은 기록은 남아야 대사가 가능하다.
    sender_user_id    BIGINT        NOT NULL,
    receiver_user_id  BIGINT        NOT NULL,
    amount            NUMERIC(19,2) NOT NULL,
    message           VARCHAR(200),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_point_transfers_no UNIQUE (transfer_no),
    -- 버튼 두 번 누르기가 두 배 송금이 되지 않게 하는 최후 방어선. 응용 계층의 단축 반환이
    -- 경합으로 뚫려도 여기서 막힌다(Triple Idempotency L3).
    CONSTRAINT uq_point_transfers_request UNIQUE (sender_user_id, request_id),
    -- 자기 자신에게 보내기 금지. 레거시에서 이것은 유효기간을 초기화하는 수단이었다.
    -- 도메인 팩토리가 먼저 막지만, 배치나 수기 SQL 이 도메인을 우회해도 표가 스스로를 지킨다.
    CONSTRAINT chk_point_transfers_not_self     CHECK (sender_user_id <> receiver_user_id),
    CONSTRAINT chk_point_transfers_positive     CHECK (amount > 0),
    -- 포인트는 원 단위다. 소수점이 들어오면 어딘가에서 나눈 값이 그대로 흘러온 것이다.
    CONSTRAINT chk_point_transfers_integral     CHECK (amount = trunc(amount))
);

-- 이력 화면은 "내가 보낸 것 + 내가 받은 것"을 최신순으로 섞어 본다. 방향별로 인덱스를 나눈다 —
-- OR 조건 하나로는 어느 쪽도 타지 못한다.
CREATE INDEX idx_point_transfers_sender   ON point_transfers (sender_user_id, created_at DESC);
CREATE INDEX idx_point_transfers_receiver ON point_transfers (receiver_user_id, created_at DESC);

-- 받은 선물은 새 로트로 쌓인다. 판촉성 적립이 아니므로(회사가 새로 얹어 준 몫이 아니라 이미
-- 인식된 부채의 주인이 바뀐 것) 출처를 따로 둔다 — 판촉비를 다시 잡으면 같은 포인트에 비용이
-- 두 번 계상된다.
ALTER TABLE point_lots DROP CONSTRAINT chk_point_lots_origin;
ALTER TABLE point_lots ADD CONSTRAINT chk_point_lots_origin
    CHECK (origin IN ('CHARGE_PRINCIPAL', 'CHARGE_BONUS', 'ORDER_EARN', 'MANUAL_GRANT',
                      'REFUND_RESTORE', 'TRANSFER_IN'));

COMMENT ON TABLE  point_transfers IS '회원 간 포인트 선물. 행의 존재가 곧 양쪽 원장 기입 완료를 뜻한다(상태 칼럼 없음)';
COMMENT ON COLUMN point_transfers.transfer_no IS '사람이 보는 번호이자 양쪽 원장 엔트리의 reference_id. point_transfer_no_seq 가 발급';
COMMENT ON COLUMN point_transfers.request_id IS '화면이 만드는 멱등 키. (sender_user_id, request_id) 가 유일';
