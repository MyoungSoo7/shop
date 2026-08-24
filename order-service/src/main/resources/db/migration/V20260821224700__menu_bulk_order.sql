-- 대량주문 화면 메뉴 시드.
--
-- 네비게이션의 정본은 menus 테이블이다(프론트 셸은 GET /api/menus/me 로 그린다). 라우트만 만들고
-- 이 행을 넣지 않으면 화면은 존재하는데 아무도 도달할 수 없다 — menu-route-gate 가 그 상태를 막는다.
--
-- SHOP 영역 최상위인 이유: 대량주문은 관리자 운영 기능이 아니라 <구매자가 자기 주문을 올리는>
-- 경로다. 파일 안의 수령인 정보는 올린 사람 본인의 것이고, 초안도 올린 사람만 볼 수 있다.
--
-- '주문하기'(sort_order 10 대) 다음, '내 포인트·상품권'(30) 앞에 둔다 — 주문 흐름에 붙는 화면이다.

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type, sort_order, required_role, visible, active)
SELECT NULL, '대량주문', '/order/bulk', '📦', 'CSV 업로드 → 검증 → 실주문 전환', 'SHOP', 'ITEM', 20, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/order/bulk');
