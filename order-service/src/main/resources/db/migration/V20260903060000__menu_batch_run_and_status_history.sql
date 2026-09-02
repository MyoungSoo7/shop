-- ============================================================
-- V20260903060000 : 시스템 관리 그룹에 '배치 실행 원장' · '주문 상태 이력' 등록
--
--   · 배치 실행 원장   /admin/system/batch-runs             — 배치별 마지막 성공 · 이력 · 재실행
--   · 주문 상태 이력   /admin/system/order-status-history   — 상태 전이 · 체류 시간 · 이력 누락 대조
--
-- 둘 다 "서버는 있는데 부르는 화면이 없는" 상태를 만들지 않으려고 기능과 같이 붙인다.
-- api-screen-gate 의 PENDING_BUDGET 이 0 이라 미루려면 그 숫자를 올려야 하는데, 이 둘은
-- 미룰 이유가 없다 — 기계가 부르는 트리거가 아니라 사람이 여는 운영 화면이다.
--
-- 배치 실행 원장: 지금까지 "어제 그 배치가 돌았나" 에 답할 방법이 없었다. ShedLock 의
--   shedlock 테이블은 *락을 잡았다는 사실*만 남기지 결과를 남기지 않아서, 잡고 나서 예외로
--   죽은 실행과 성공한 실행이 그 표에서는 구별되지 않는다.
-- 주문 상태 이력: 답이 운영 DB 의 order_status_history 를 손으로 조회하는 것뿐이었다.
--   그건 CS 에게 DB 접근 권한을 주거나 개발자가 매번 대신 조회하거나 둘 중 하나를 뜻했다.
--
-- 자리·URL·권한의 근거는 V20260828180000(상품 옵션)과 같다. 이 파일에 해당하는 것만 적으면:
--   ① sort_order 는 그룹 맨 뒤다. 중간에 끼우면 뒤 항목의 sort_order 가 겹치고,
--      menuFallback.ts 사본과 순서가 어긋나면 menu-route-gate 가 잡는다.
--   ② 화면 URL 은 API 경로와 달라야 한다. /admin/batch-runs 와 /orders/admin/{id}/status-history
--      는 게이트웨이로 노출된 API 라, 화면이 같은 URL 을 쓰면 새로고침 때 JSON 이 렌더된다.
--   ③ required_role 은 서버 매처를 그대로 옮긴다. 배치 원장은 ADMIN(SecurityConfig 의
--      /admin/batch-runs/** — 조회까지 같은 등급으로 묶은 이유는 이 표가 실패 사유 문자열을
--      그대로 담기 때문이다). 상태 이력은 ADMIN·MANAGER(/orders/admin/**) — 실제로 이 화면을
--      여는 쪽이 CS 다.
--
-- 0903 대 앞 번호(040000·050000)는 이 브랜치가 이미 쓰고 있다. Flyway 는 버전이 겹치면
-- 부팅에서 통째로 죽는데, 병렬 세션이 올린 파일은 이름이 달라 git 이 충돌로 보지 않는다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '배치 실행 원장', '/admin/system/batch-runs', '🗓️',
       '배치별 마지막 성공 · 실행 이력 · 놓친 날짜분 재실행', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/batch-runs');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '주문 상태 이력', '/admin/system/order-status-history', '🧭',
       '주문 한 건의 상태 전이 · 체류 시간 · 이력 누락 대조', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/order-status-history');
