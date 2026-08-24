-- common_code_groups: 공통코드 그룹
CREATE TABLE common_code_groups (
    group_code  VARCHAR(50)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT pk_common_code_groups PRIMARY KEY (group_code)
);

-- common_codes: 공통코드 항목
CREATE TABLE common_codes (
    id          BIGSERIAL    NOT NULL,
    group_code  VARCHAR(50)  NOT NULL,
    code        VARCHAR(50)  NOT NULL,
    label       VARCHAR(100) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT true,
    extra1      VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT pk_common_codes PRIMARY KEY (id),
    CONSTRAINT uq_common_codes_group_code UNIQUE (group_code, code),
    CONSTRAINT fk_common_codes_group FOREIGN KEY (group_code)
        REFERENCES common_code_groups (group_code)
);

CREATE INDEX idx_common_codes_group_code ON common_codes (group_code);

-- 시드 데이터: ORDER_STATUS 그룹
INSERT INTO common_code_groups (group_code, name, description, active, created_at, updated_at)
VALUES ('ORDER_STATUS', '주문상태', '주문 라이프사이클 상태 코드', true, now(), now());

INSERT INTO common_codes (group_code, code, label, sort_order, active, created_at, updated_at)
VALUES
    ('ORDER_STATUS', 'CREATED',   '주문생성',   10, true, now(), now()),
    ('ORDER_STATUS', 'PAID',      '결제완료',   20, true, now(), now()),
    ('ORDER_STATUS', 'CANCELED',  '주문취소',   30, true, now(), now()),
    ('ORDER_STATUS', 'REFUNDED',  '환불완료',   40, true, now(), now());

-- 시드 데이터: SETTLEMENT_STATUS 그룹
INSERT INTO common_code_groups (group_code, name, description, active, created_at, updated_at)
VALUES ('SETTLEMENT_STATUS', '정산상태', '정산 처리 상태 코드', true, now(), now());

INSERT INTO common_codes (group_code, code, label, sort_order, active, created_at, updated_at)
VALUES
    ('SETTLEMENT_STATUS', 'REQUESTED',  '정산요청',   10, true, now(), now()),
    ('SETTLEMENT_STATUS', 'PROCESSING', '정산처리중', 20, true, now(), now()),
    ('SETTLEMENT_STATUS', 'DONE',       '정산완료',   30, true, now(), now()),
    ('SETTLEMENT_STATUS', 'FAILED',     '정산실패',   40, true, now(), now());
