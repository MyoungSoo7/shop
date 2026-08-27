-- ============================================================
-- V20260828190000 : 이벤트 프로모션 화면 두 개 메뉴 등록
--
--   · 이벤트            /promotions                  — 출석체크 · 럭키박스 참여 (SHOP)
--   · 이벤트 프로모션   /admin/system/promotions     — 캠페인 등록 · 여닫기 · 경품 (SYSTEM)
--
-- 두 화면이 부르는 API 는 이 서비스가 아니라 marketing-service(8096)가 서빙한다(ADR 0045).
-- 그래도 메뉴 행이 여기 있는 이유는 메뉴 원장이 order-service 의 menus 한 벌뿐이기 때문이다 —
-- 서비스마다 메뉴 테이블을 두면 좌측 네비를 누가 그리는지가 흐려진다.
--
-- 화면 URL 이 API 경로와 다른 이유는 앞선 메뉴 마이그레이션들과 같다.
--   ① 구매자 화면 /promotions 는 API(/api/promotions)와 세그먼트가 겹치지만 안전하다.
--      nginx 두 벌이 게이트웨이로 넘기는 세그먼트 목록에 promotions 가 없기 때문이다.
--      (categories·orders 는 목록에 있어서 /browse·/order/… 로 피해 갔다.)
--   ② 운영자 화면은 /admin/promotions 가 아니라 /admin/system/promotions 다. nginx SPA
--      폴백이 /admin 하위 중 정해진 네비 그룹(system·operation·shipping·approvals·login)만
--      index.html 로 돌려주고, 그 밖은 새로고침에서 404 가 된다. 개발 서버에서는 안 드러난다.
--
-- required_role: 구매자 쪽은 USER(로그인해야 출석이 누구 것인지 정해진다), 운영자 쪽은
-- ADMIN 이다. 캠페인을 여는 것은 구매자에게 즉시 노출되고 보상 지급이 시작되는 조작이라,
-- 조회 콘솔들과 달리 MANAGER 에게 열지 않는다 — marketing 의 SecurityConfig 도
-- /admin/promotions/** 를 ADMIN 으로 막는다.
--
-- sort_order 65 : SHOP 최상위가 7·8·20·25·30·35·40·45·50·55·60 으로 차 있어 맨 뒤가
-- 유일하게 빈 자리다. 뒤를 미는 UPDATE 는 두지 않는다(끼어들지 않으므로 필요 없다).
--
-- 0828 대 앞 번호(090000~180000)는 이미 차 있다. Flyway 는 버전이 겹치면 부팅에서 통째로
-- 죽는데, 병렬 세션이 올린 파일은 이름이 달라 git 이 충돌로 보지 않는다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT NULL, '이벤트', '/promotions', '🎁',
       '출석체크 · 럭키박스 참여', 'SHOP', 'ITEM', 65, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/promotions');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '이벤트 프로모션', '/admin/system/promotions', '🎉',
       '출석체크 · 럭키박스 캠페인 등록 · 여닫기 · 경품', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/promotions');
