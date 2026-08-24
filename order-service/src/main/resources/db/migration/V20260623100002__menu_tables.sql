-- ============================================================
-- V20260623100002 : menus 테이블 생성 + 시드 데이터
-- ============================================================

CREATE TABLE IF NOT EXISTS menus (
    id            BIGSERIAL    PRIMARY KEY,
    parent_id     BIGINT       NULL REFERENCES menus(id),
    name          VARCHAR(100) NOT NULL,
    path          VARCHAR(255) NULL,
    icon          VARCHAR(50)  NULL,
    sort_order    INT          NOT NULL DEFAULT 0,
    required_role VARCHAR(20)  NULL,
    visible       BOOLEAN      NOT NULL DEFAULT TRUE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_menus_parent_id   ON menus(parent_id);
CREATE INDEX IF NOT EXISTS idx_menus_sort_order  ON menus(sort_order);

-- ============================================================
-- 시드: 시스템 관리 메뉴 트리
-- ============================================================

-- 1) 최상위: 시스템 관리
INSERT INTO menus (name, path, icon, sort_order, required_role, visible, active)
VALUES ('시스템 관리', '/admin/system', '⚙', 0, NULL, TRUE, TRUE);

-- 2) 하위 메뉴 3개 (parent_id = 위에서 삽입된 '시스템 관리' id)
INSERT INTO menus (parent_id, name, path, icon, sort_order, required_role, visible, active)
SELECT id, '메뉴 관리',     '/admin/system/menus', NULL, 0, 'ADMIN', TRUE, TRUE
FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL;

INSERT INTO menus (parent_id, name, path, icon, sort_order, required_role, visible, active)
SELECT id, '공통코드 관리', '/admin/system/codes', NULL, 1, 'ADMIN', TRUE, TRUE
FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL;

INSERT INTO menus (parent_id, name, path, icon, sort_order, required_role, visible, active)
SELECT id, 'RBAC 관리',    '/admin/system/rbac',  NULL, 2, 'ADMIN', TRUE, TRUE
FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL;
