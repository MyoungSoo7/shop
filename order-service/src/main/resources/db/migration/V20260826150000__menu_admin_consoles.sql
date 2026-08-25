-- ============================================================
-- V20260826150000 : 시스템 관리 그룹에 관리자 콘솔 4종 등록
--
-- 버전 번호가 100000 이 아닌 이유: 같은 날 V20260826100000__order_shipping_address_snapshot.sql
-- 이 이미 그 번호를 쓰고 있다. Flyway 는 버전이 겹치면 스키마 비교 이전에
-- CompositeMigrationResolver 에서 통째로 죽는다 — 그러면 이 서비스의 DB 통합테스트가 전부
-- 같은 예외로 실패해서 원인이 메뉴 시드처럼 보이지 않는다.
--
--   · 권한 계정   /admin/system/operators     — 조작 권한이 있는 계정의 명부 · 잠금 해제
--   · 지표 추이   /admin/system/trends        — 대시보드 카드를 날짜 축으로 편 것
--   · 판매 통계   /admin/system/sales-stats   — 상품 랭킹 · 카테고리 분포
--   · 작업 큐     /admin/system/order-queues  — 밀린 주문을 할 일 단위로 묶고 기한을 매긴 것
--
-- 화면 URL 이 API 경로(/admin/operators, /api/ops/**, /admin/sales, /admin/order-queues)와
-- 다른 이유는 종전과 같다. ① nginx SPA 폴백은 /admin/(system|operation|shipping|approvals|login)
-- 만 index.html 로 내려보내므로 다른 접두사는 새로고침에서 404 가 된다(vite dev 에서는 안 보인다).
-- ② 화면 URL 을 API 와 같게 두면 새로고침 때 API JSON 이 그대로 브라우저에 렌더된다.
--
-- required_role 은 서버가 실제로 막는 등급을 그대로 옮긴다. /admin/order-queues 만 ADMIN·MANAGER 다
-- — 밀린 주문을 실제로 처리하는 쪽이 MANAGER 이기 때문이다. 나머지 셋은 ADMIN 전용이다.
-- 메뉴만 넓히면 눌러서 403 을 받는 링크가 되고, 메뉴만 좁히면 권한 있는 사람이 화면을 못 찾는다.
--
-- 자리는 그룹의 맨 뒤에 잇는다. 중간에 끼우려면 뒤 항목들의 sort_order 를 통째로 밀어야 하는데,
-- 이 그룹은 이미 빈틈없이 차 있어 한 항목만 옮기면 그 자리의 항목과 겹친다(V20260820100000 참조).
-- 네 문장이 순서대로 실행되며 각자 그 시점의 MAX+1 을 잡으므로 서로 겹치지 않는다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '권한 계정', '/admin/system/operators', '🛡️',
       '조작 권한 계정 명부 · 미사용 회수 · 잠금 해제', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/operators');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '지표 추이', '/admin/system/trends', '📈',
       '대시보드 지표의 일자별 추이 · 기간 합계', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/trends');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '판매 통계', '/admin/system/sales-stats', '📊',
       '상품 판매 랭킹 · 카테고리별 분포', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/sales-stats');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '작업 큐', '/admin/system/order-queues', '📮',
       '밀린 주문 · 기한 초과 · 최장 대기', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/order-queues');
