-- 회원 관리 콘솔 메뉴 시드.
--
-- 네비게이션의 정본은 menus 테이블이다(프론트 셸은 GET /api/menus/me 로 그린다). 라우트만 만들고
-- 이 행을 넣지 않으면 화면은 존재하는데 아무도 도달할 수 없다 — menu-route-gate 가 그 상태를 막는다.
--
-- 승인·정지 조작은 이전에도 API 로 존재했지만 대상을 찾을 화면이 없었다. 이 메뉴가 그 진입점이다.

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type, sort_order, required_role, visible, active)
SELECT p.id, '회원 관리', '/admin/system/members', '👤', '회원 검색 · 승인 · 역할 변경', 'SYSTEM', 'ITEM', 14, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/members');
