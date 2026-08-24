-- ============================================================
-- V20260815100000 : 시스템 관리 그룹에 '게시판 관리' 화면 등록 + SYSTEM_BOARD_MANAGE 권한
--
-- board-service(8114, lemuel_board)의 관리 콘솔 진입점이다. 게시판 하나가 곧 화면 하나이므로
-- 화면 구성을 바꾸는 다른 시스템 콘솔(메뉴·코드·RBAC)과 같은 ADMIN 등급으로 둔다.
--
-- ★ 권한 코드는 이 메뉴의 노출 필터링용이다. board-service 의 실제 인가는 역할(JWT role)로 한다
--   — 권한 코드로 판정하면 board-service 가 order DB 의 permissions 를 읽어야 해서 DB-per-service
--   경계가 무너진다(docs/plan/board-service.md §3·§7).
--
-- 정렬은 증빙 리뷰 큐(7) 뒤인 8. 기존 항목의 sort_order 를 건드리지 않는다 — 재배치는 운영
-- 화면에서 할 수 있는 표시 속성 변경이고, 마이그레이션이 매번 흔들면 그 편집이 되돌려진다.
-- ============================================================

INSERT INTO permissions (code, name, category, description)
VALUES ('SYSTEM_BOARD_MANAGE', '게시판 관리', 'SYSTEM', '게시판 정의 생성 · 정책 변경 · 개폐')
ON CONFLICT (code) DO NOTHING;

-- ADMIN 은 전 권한 보유자다. 최초 시드는 CROSS JOIN 으로 한 번에 부여했으므로, 이후에 추가되는
-- 권한은 이렇게 명시적으로 이어 붙여야 한다(안 하면 메뉴가 ADMIN 에게도 안 보인다).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'SYSTEM_BOARD_MANAGE'
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, required_permission, visible, active)
SELECT p.id, '게시판 관리', '/admin/system/boards', '📋',
       '게시판 생성 · 스킨 · 권한 정책', 'SYSTEM', 'ITEM', 8, 'ADMIN', 'SYSTEM_BOARD_MANAGE', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL;
