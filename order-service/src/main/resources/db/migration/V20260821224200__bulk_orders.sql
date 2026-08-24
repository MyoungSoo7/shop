-- 대량주문 초안 — 업로드 → 검증 → 확정.
--
-- 레거시 커머스(ssgb2e-front `OrderMultiController` / `OrderMultiServiceImpl`, 2,200 줄)의 뼈대를 옮긴 것이다:
--   업로드 → orderMultiUploadCheck(검증·오류행 리포트) → 임시주문 적재
--          → tmpOrderToRealOrder(확정 전환) / tmpOrderDelete / reUploadOrderMulti(재등록)
--
-- 핵심은 **검증과 확정의 분리**다. 한 번에 처리하면 수백 행 파일에서 뒷쪽 한 행이 틀렸을 때
-- 앞쪽 수백 건이 이미 실주문으로 나가 있고, 되돌리는 일이 그대로 취소·환불 작업이 된다.

CREATE TABLE IF NOT EXISTS bulk_order_drafts (
    id                BIGSERIAL PRIMARY KEY,
    uploader_user_id  BIGINT      NOT NULL REFERENCES users(id),
    file_name         VARCHAR(255),
    status            VARCHAR(20) NOT NULL
                      CHECK (status IN ('UPLOADED', 'VALIDATED', 'REJECTED', 'CONFIRMED', 'DISCARDED')),
    uploaded_at       TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bulk_order_drafts_uploader
    ON bulk_order_drafts (uploader_user_id, uploaded_at DESC);

CREATE TABLE IF NOT EXISTS bulk_order_rows (
    id                BIGSERIAL PRIMARY KEY,
    draft_id          BIGINT  NOT NULL REFERENCES bulk_order_drafts(id) ON DELETE CASCADE,
    -- 'row_number' 는 PG 윈도우 함수 이름과 겹쳐 인용 없이 쓰면 문맥에 따라 헷갈린다 — line_number 로 둔다.
    line_number       INT     NOT NULL,
    valid             BOOLEAN NOT NULL DEFAULT FALSE,
    error_message     VARCHAR(1000),
    -- 이미 주문이 나간 행. 재확정에서 건너뛰는 유일한 근거 — 없으면 재시도가 곧 중복 주문이다.
    created_order_id  BIGINT REFERENCES orders(id),
    CONSTRAINT uq_bulk_order_row UNIQUE (draft_id, line_number)
);

CREATE INDEX IF NOT EXISTS idx_bulk_order_rows_draft ON bulk_order_rows (draft_id);

-- 셀 단위로 결과를 남긴다: 행 단위 메시지만 있으면 열 30 개짜리 양식에서 운영자는
-- "이 행 어딘가가 틀렸다"만 알게 되고 결국 눈으로 훑는다.
CREATE TABLE IF NOT EXISTS bulk_order_cells (
    id             BIGSERIAL PRIMARY KEY,
    row_id         BIGINT  NOT NULL REFERENCES bulk_order_rows(id) ON DELETE CASCADE,
    column_index   INT     NOT NULL,
    cell_value     VARCHAR(500),
    valid          BOOLEAN NOT NULL DEFAULT FALSE,
    error_message  VARCHAR(500),
    CONSTRAINT uq_bulk_order_cell UNIQUE (row_id, column_index)
);

CREATE INDEX IF NOT EXISTS idx_bulk_order_cells_row ON bulk_order_cells (row_id);

-- 검증 규칙은 코드가 아니라 데이터다(레거시 `item_validate_type` 의 계승).
-- 양식에 열이 늘거나 "이 항목도 필수로" 같은 요구는 배포 없이 행 하나로 끝난다 —
-- 대량주문 양식은 고객사·시즌마다 바뀌는 종류의 것이다.
CREATE TABLE IF NOT EXISTS bulk_order_column_specs (
    id               BIGSERIAL PRIMARY KEY,
    column_index     INT         NOT NULL UNIQUE,
    item_code        VARCHAR(50) NOT NULL UNIQUE,
    name             VARCHAR(100) NOT NULL,
    required         BOOLEAN     NOT NULL DEFAULT FALSE,
    max_length       INT,
    validation_type  VARCHAR(20) NOT NULL DEFAULT 'NONE'
                     CHECK (validation_type IN ('NONE', 'ALNUM', 'NUMERIC', 'ENUM', 'PHONE', 'EMAIL')),
    validation_text  VARCHAR(500)
);

INSERT INTO bulk_order_column_specs
    (column_index, item_code, name, required, max_length, validation_type, validation_text)
VALUES
    (0, 'product_id',      '상품번호',   TRUE,   18, 'NUMERIC', NULL),
    (1, 'quantity',        '수량',       TRUE,    6, 'NUMERIC', NULL),
    (2, 'recipient_name',  '수령인',     TRUE,   50, 'ALNUM',   NULL),
    (3, 'recipient_phone', '연락처',     TRUE,   20, 'PHONE',   NULL),
    (4, 'postal_code',     '우편번호',   TRUE,    6, 'NUMERIC', NULL),
    (5, 'address1',        '주소',       TRUE,  200, 'NONE',    NULL),
    (6, 'address2',        '상세주소',   FALSE, 200, 'NONE',    NULL),
    (7, 'delivery_memo',   '배송메모',   FALSE, 100, 'NONE',    NULL)
ON CONFLICT (column_index) DO NOTHING;

COMMENT ON TABLE bulk_order_column_specs IS '대량주문 업로드 양식 정의 — 검증 규칙을 데이터로 둔다(배포 없이 양식 변경)';
COMMENT ON COLUMN bulk_order_rows.created_order_id IS '확정으로 생성된 주문. 재확정에서 이 행을 건너뛰는 근거(중복 주문 방지)';
