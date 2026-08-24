-- 포인트·기프트카드 화면 메뉴 시드.
--
-- 네비게이션의 정본은 menus 테이블이다(프론트 셸은 GET /api/menus/me 로 그린다). 라우트만 만들고
-- 이 행을 넣지 않으면 화면은 존재하는데 아무도 도달할 수 없다 — menu-route-gate 가 그 상태를 막는다.
--
-- 관리자 2건은 '시스템 관리' 하위, 사용자 1건은 최상위 SHOP 영역이다.

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type, sort_order, required_role, visible, active)
SELECT p.id, '포인트 운영', '/admin/system/points', '🪙', '수기 지급 · 유효기간 소멸', 'SYSTEM', 'ITEM', 11, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/points');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type, sort_order, required_role, visible, active)
SELECT p.id, '기프트카드 운영', '/admin/system/gift-cards', '🎁', '상품권 발행 · 유효기간 소멸', 'SYSTEM', 'ITEM', 12, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/gift-cards');

-- 사용자 화면 — 결제 화면에서 "얼마까지 낼 수 있나"를 확인하러 오는 경로다.
INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type, sort_order, required_role, visible, active)
SELECT NULL, '내 포인트·상품권', '/my/balances', '🪙', NULL, 'SHOP', 'ITEM', 30, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/my/balances');
