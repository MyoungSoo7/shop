-- 감사 로그 콘솔 메뉴 시드.
--
-- 네비게이션의 정본은 menus 테이블이다(프론트 셸은 GET /api/menus/me 로 그린다). 라우트만 만들고
-- 이 행을 넣지 않으면 화면은 존재하는데 아무도 도달할 수 없다 — menu-route-gate 가 그 상태를 막는다.
--
-- 한 화면이 두 서비스의 감사 테이블(커머스/정산)을 탭으로 나눠 본다. 테이블이 서비스마다 따로
-- 있는 것은 MSA 경계 때문이고, 메뉴까지 둘로 나누면 운영자가 "어디서 봐야 하는지"를 매번 골라야
-- 하므로 진입점은 하나로 둔다.

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type, sort_order, required_role, visible, active)
SELECT p.id, '감사 로그', '/admin/system/audit-logs', '🔎', '조작 이력 조회 (커머스 · 정산)', 'SYSTEM', 'ITEM', 13, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/audit-logs');
