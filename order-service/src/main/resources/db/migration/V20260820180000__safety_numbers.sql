-- V20260820180000: 안심번호(수취인 가상번호) 풀
--
-- [문제]
--   배송 정보(shipments.phone)에는 수취인 실번호가 그대로 들어 있고, 배송 조회 응답도 그 값을
--   그대로 돌려준다. 기사·판매자·운영 화면 어디로든 실번호가 흘러나간다.
--
-- [조치]
--   유한한 가상번호 풀을 두고 주문마다 하나를 배정한다. 배송이 끝날 무렵(유효기간 만료) 회수해
--   다음 주문이 재사용한다.
--
-- [범위 한계 — 반드시 읽을 것]
--   이 테이블과 도메인은 번호의 배정·수명·노출만 관리한다. 050 번호가 실제로 실번호로 착신
--   전환되려면 통신사(안심번호 사업자) 연동이 필요하며 그 연동은 이 저장소에 없다. 지금 보장되는
--   것은 "실번호가 API 응답에 노출되지 않는다"까지다.
--
-- [불변식]
--   · 주문당 배정은 하나 — 부분 UNIQUE 인덱스가 강제한다. 두 개가 붙으면 어느 번호로 오는
--     전화가 이 주문인지 알 수 없다.
--   · ASSIGNED 인데 주문·만료시각이 비어 있으면 회수 스캔이 그 행을 영원히 지나친다(풀이 마른다)
--     → CHECK 로 막는다.

CREATE TABLE IF NOT EXISTS safety_numbers (
    id             BIGSERIAL   PRIMARY KEY,
    virtual_number VARCHAR(20) NOT NULL UNIQUE,
    status         VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    order_id       BIGINT,
    assigned_at    TIMESTAMPTZ,
    expires_at     TIMESTAMPTZ,

    CONSTRAINT ck_safety_numbers_status CHECK (status IN ('AVAILABLE', 'ASSIGNED')),
    CONSTRAINT ck_safety_numbers_assigned CHECK (
        status <> 'ASSIGNED' OR (order_id IS NOT NULL AND expires_at IS NOT NULL)),
    CONSTRAINT ck_safety_numbers_available CHECK (
        status <> 'AVAILABLE' OR (order_id IS NULL AND expires_at IS NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_safety_numbers_order
    ON safety_numbers (order_id)
    WHERE status = 'ASSIGNED';

-- 회수 스캔(만료된 배정만) — 배정분은 전체의 소수라 부분 인덱스가 유리하다.
CREATE INDEX IF NOT EXISTS idx_safety_numbers_expiry
    ON safety_numbers (expires_at)
    WHERE status = 'ASSIGNED';

COMMENT ON TABLE safety_numbers IS
    '안심번호 풀 — 주문당 가상번호 1개 배정, 만료 시 회수해 재사용. 착신 전환은 통신사 연동 몫(미구현)';

-- 데모용 풀 50개. 운영에서는 사업자에게 발급받은 번호 대역을 넣는다.
INSERT INTO safety_numbers (virtual_number)
SELECT '050-9999-' || LPAD(seq::text, 4, '0')
  FROM generate_series(1, 50) AS seq
ON CONFLICT (virtual_number) DO NOTHING;
