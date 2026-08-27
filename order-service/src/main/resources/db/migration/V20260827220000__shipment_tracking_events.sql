-- 배송 추적 이력.
--
-- 지금까지 배송의 시각은 shipments 에 shipped_at 과 delivered_at 둘뿐이었다. 그 사이의 사건
-- (출고 준비, 택배사 첫 스캔, 배송지 변경)은 status 칸을 덮어쓰며 지나가고 흔적을 남기지 않았다.
-- 그래서 고객이 보는 것은 상태 단어 하나였고, "언제부터 이 상태인지"도 "왜 안 움직이는지"도
-- 답할 근거가 없었다 — 문의가 오면 운영자도 마찬가지였다.
--
-- 이 테이블은 그 사건들을 한 줄씩 남긴다. 정본은 우리 시스템의 전이(source='INTERNAL')다.
-- 택배사 스캔은 조회 시점에 가져와 화면에서만 합치고 여기 저장하지 않는다 — 저장하면 택배사의
-- 정정(시각 수정·이벤트 취소)을 우리가 되돌릴 방법이 없어 두 기록이 영구히 어긋난다.
--
-- 소급 적재(backfill)는 하지 않는다. 이 테이블이 생기기 전 배송에는 이력이 없고, 그때는 조회
-- 서비스가 shipments 가 실제로 들고 있는 시각(created_at/shipped_at/delivered_at)만으로 최소
-- 타임라인을 합성한다. 없는 사실을 그럴듯한 시각과 함께 적어 넣는 것보다, 아는 것만 보이는
-- 쪽이 낫다.

CREATE TABLE IF NOT EXISTS opslab.shipment_tracking_events (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,               -- 이 시점의 배송 상태
    source       VARCHAR(20)  NOT NULL DEFAULT 'INTERNAL',
    description  VARCHAR(500) NOT NULL,               -- 사람이 읽는 설명
    location     VARCHAR(200),                        -- 택배사 스캔 지점 (내부 이벤트에는 없음)
    occurred_at  TIMESTAMP    NOT NULL,               -- 실제 발생 시각 (적재 시각이 아님)
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_shipment_tracking_events_order
        FOREIGN KEY (order_id) REFERENCES opslab.orders(id) ON DELETE CASCADE,
    CONSTRAINT chk_shipment_tracking_events_status
        CHECK (status IN ('PENDING', 'READY', 'SHIPPED', 'IN_TRANSIT', 'DELIVERED', 'RETURNED')),
    CONSTRAINT chk_shipment_tracking_events_source
        CHECK (source IN ('INTERNAL', 'CARRIER'))
);

-- 조회는 언제나 "주문 하나의 이력을 시간순으로" 다. 정렬까지 인덱스가 받아 준다.
CREATE INDEX IF NOT EXISTS idx_shipment_tracking_events_order
    ON opslab.shipment_tracking_events (order_id, occurred_at);

COMMENT ON TABLE opslab.shipment_tracking_events IS
    '배송 상태 전이 이력. 추가만 하고 수정하지 않는다 — 이미 일어난 일의 시각·문구를 고칠 수 '
    '있으면 타임라인은 사실 기록이 아니라 편집 가능한 서술이 된다.';

COMMENT ON COLUMN opslab.shipment_tracking_events.source IS
    'INTERNAL=우리 시스템의 상태 전이(저장됨), CARRIER=택배사 스캔(조회 시점에만 합쳐지며 저장되지 않음)';

COMMENT ON COLUMN opslab.shipment_tracking_events.occurred_at IS
    '사건이 실제로 일어난 시각. created_at(적재 시각)과 다를 수 있다.';
