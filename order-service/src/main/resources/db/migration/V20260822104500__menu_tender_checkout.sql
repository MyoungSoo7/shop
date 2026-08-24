-- 나눠 결제(텐더 결제) 화면 메뉴 시드.
--
-- 네비게이션 정본은 menus 테이블이다(프론트 셸은 GET /api/menus/me 로 그린다). 라우트만 만들고
-- 이 행을 넣지 않으면 화면은 존재하는데 아무도 도달할 수 없다 — menu-route-gate 가 그 상태를 막는다.
--
-- 이 화면이 늦게 생긴 이유를 남긴다: 포인트 원장·기프트카드 원장·입금 대기 선점까지 백엔드를
-- 세 겹으로 쌓는 동안 /payments/split 을 부르는 화면이 하나도 없었다. 고객은 "내 포인트"를 볼
-- 수만 있고 쓸 수는 없었다(docs/plan/point-ledger.md §6 ③ "남은 것: 프론트 체크아웃 UI").
--
-- SHOP 영역 최상위, 대량주문(20) 다음·내 잔액(30) 앞에 둔다 — 주문에서 결제로 이어지는 순서다.

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type, sort_order, required_role, visible, active)
SELECT NULL, '나눠 결제', '/order/pay', '💳', '포인트·상품권·카드 혼합 결제', 'SHOP', 'ITEM', 25, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/order/pay');
