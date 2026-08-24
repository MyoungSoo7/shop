-- ============================================================
-- RBAC: roles / permissions / role_permissions
-- ============================================================

CREATE TABLE IF NOT EXISTS roles (
    id          BIGSERIAL     PRIMARY KEY,
    code        VARCHAR(30)   NOT NULL UNIQUE,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(255),
    builtin     BOOLEAN       NOT NULL DEFAULT false,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS permissions (
    id          BIGSERIAL     PRIMARY KEY,
    code        VARCHAR(60)   NOT NULL UNIQUE,
    name        VARCHAR(100)  NOT NULL,
    category    VARCHAR(40)   NOT NULL,
    description VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- ============================================================
-- 시드: roles
-- ============================================================
INSERT INTO roles (code, name, description, builtin)
VALUES
    ('ADMIN',   '최고 관리자',  '시스템 전체 권한을 가진 최고 관리자', true),
    ('MANAGER', '매니저',       '운영 매니저',                        true),
    ('USER',    '일반 사용자',  '일반 구매 회원',                     true)
ON CONFLICT (code) DO NOTHING;

-- ============================================================
-- 시드: permissions
-- ============================================================
INSERT INTO permissions (code, name, category, description)
VALUES
    -- ORDER
    ('ORDER_READ',           '주문 조회',       'ORDER',      '주문 목록 및 상세 조회'),
    ('ORDER_CANCEL',         '주문 취소',       'ORDER',      '주문 취소 처리'),
    -- PRODUCT
    ('PRODUCT_READ',         '상품 조회',       'PRODUCT',    '상품 목록 및 상세 조회'),
    ('PRODUCT_WRITE',        '상품 등록/수정',  'PRODUCT',    '상품 등록 및 수정'),
    -- SETTLEMENT
    ('SETTLEMENT_READ',      '정산 조회',       'SETTLEMENT', '정산 내역 조회'),
    ('SETTLEMENT_CONFIRM',   '정산 확정',       'SETTLEMENT', '정산 확정 처리'),
    -- USER
    ('USER_MANAGE',          '회원 관리',       'USER',       '회원 정보 조회 및 관리'),
    -- COUPON
    ('COUPON_MANAGE',        '쿠폰 관리',       'COUPON',     '쿠폰 생성/수정/삭제'),
    -- SYSTEM
    ('SYSTEM_MENU_MANAGE',   '메뉴 관리',       'SYSTEM',     '시스템 메뉴 구성 관리'),
    ('SYSTEM_CODE_MANAGE',   '공통코드 관리',   'SYSTEM',     '공통 코드 관리'),
    ('SYSTEM_RBAC_MANAGE',   'RBAC 관리',       'SYSTEM',     '역할 및 권한 관리')
ON CONFLICT (code) DO NOTHING;

-- ============================================================
-- 시드: role_permissions
-- ADMIN  = 전체 권한
-- MANAGER = ORDER_READ / ORDER_CANCEL / PRODUCT_READ / PRODUCT_WRITE / SETTLEMENT_READ
-- USER   = (없음)
-- ============================================================

-- ADMIN: 모든 permission
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

-- MANAGER: 지정 permission
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'ORDER_READ', 'ORDER_CANCEL',
    'PRODUCT_READ', 'PRODUCT_WRITE',
    'SETTLEMENT_READ'
)
WHERE r.code = 'MANAGER'
ON CONFLICT DO NOTHING;
