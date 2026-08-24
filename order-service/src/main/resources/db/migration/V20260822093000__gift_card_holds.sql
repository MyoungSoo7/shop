-- 기프트카드 선점 (docs/plan/gift-card-ledger.md Phase 2, point-ledger.md §14 의 자매편).
--
-- 입금 대기 결제(가상계좌·무통장)가 붙잡아 두는 카드 잔액. 포인트 선점(point_holds)과 같은 목적이지만
-- 구조가 하나 다르다.
--
-- ● 잠긴 금액을 카드에 저장하지 않는다.
--   gift_cards 에는 잔액 요약이 없고(설계 문서 §3 — "어긋날 대상이 없으므로 그 종류의 데이터 손상이
--   구조적으로 불가능하다"), 거기에 locked 컬럼을 더하면 "저장된 값 vs 선점 행의 합"이라는 손상 축을
--   새로 만든다. 가용액은 언제나 remaining − Σ(ACTIVE 선점) 으로 계산한다.
--
-- ● 행 단위가 (선점 근거 × 카드) 다.
--   상품권은 권면가 단위로 발행되어 한 장으로 못 채우는 경우가 있으므로, 선점 한 건이 카드 여러 장에
--   걸친다. 포인트는 계정이 하나라 참조당 한 행이었다.

CREATE TABLE gift_card_holds (
    id              BIGSERIAL      PRIMARY KEY,
    gift_card_id    BIGINT         NOT NULL REFERENCES gift_cards(id),
    amount          NUMERIC(19,2)  NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    reference_type  VARCHAR(50)    NOT NULL,
    reference_id    VARCHAR(100)   NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,

    -- 같은 근거로 같은 카드를 두 번 잠그지 못한다(Triple Idempotency L3).
    -- 카드 id 를 키에 넣는 것이 point_holds 와 다른 점이다 — 한 근거가 여러 장에 걸치기 때문.
    CONSTRAINT uq_gift_card_holds_reference UNIQUE (reference_type, reference_id, gift_card_id),
    CONSTRAINT chk_gift_card_holds_status
        CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT chk_gift_card_holds_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_gift_card_holds_integral CHECK (amount = trunc(amount)),
    -- 상태와 해소 시각은 함께 움직인다. 한쪽만 채워진 행은 경합을 사후 재구성할 수 없게 만든다.
    CONSTRAINT chk_gift_card_holds_resolved_pairing CHECK (
        (status = 'ACTIVE'  AND resolved_at IS NULL)
     OR (status <> 'ACTIVE' AND resolved_at IS NOT NULL)
    )
);

-- 카드별 활성 선점 합계를 묻는 질의(가용액 계산의 핫패스)용 부분 인덱스.
CREATE INDEX idx_gift_card_holds_active_card
    ON gift_card_holds (gift_card_id) WHERE status = 'ACTIVE';

-- 확정·해제는 근거로 찾아 여러 장을 한 번에 집는다.
CREATE INDEX idx_gift_card_holds_reference
    ON gift_card_holds (reference_type, reference_id);

COMMENT ON TABLE gift_card_holds IS
    '기프트카드 선점. 입금 대기 결제가 붙잡아 둔 카드 잔액의 내역. 잠긴 금액을 카드에 저장하지 않는 것이 요점 — 가용액은 remaining - SUM(ACTIVE hold) 로 계산한다.';
