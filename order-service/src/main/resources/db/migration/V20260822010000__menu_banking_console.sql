-- ============================================================
-- V20260822010000 : '수신 상품' 화면을 CEO 메뉴에 등록
--
-- 정기예금·적금·퇴직연금 3종은 도메인·서비스·컨트롤러가 모두 완성돼 있었는데 화면이 없었고,
-- 게다가 /api/banking/** 가 게이트웨이에 열려 있지 않아 화면을 만들어도 404 였다.
-- 같은 날 배선을 먼저 하고(gateway application.yml) 이 화면을 붙인다.
--
-- CEO 그룹 · '계정계 현황' 바로 뒤: 같은 서비스(account)이고, 집계를 본 다음 그 집계를 만드는
-- 개별 계약으로 내려가는 순서다.
--
-- ADMIN,MANAGER: 서버는 authenticated 만 요구하지만(계약 주체가 가입자 본인), 이 화면은
-- CEO 백오피스 맥락이라 그 등급에 맞춘다. 화면 안에서 운영자 전용 조작(운용수익 인식·수급 지급,
-- 서버가 ADMIN·MANAGER 로 막는 두 경로)을 역할로 다시 가른다.
--
-- 기준 형제는 서브쿼리가 아니라 조인으로 가져온다 — 서브쿼리면 그 경로 리터럴이 SELECT 절에
-- 남아 menu-route-gate 의 시드 추출기가 '새로 심는 메뉴'로 센다.
-- ============================================================

UPDATE menus
   SET sort_order = sort_order + 1, updated_at = NOW()
 WHERE parent_id = (SELECT id FROM menus WHERE name = 'CEO' AND parent_id IS NULL)
   AND sort_order > (SELECT sort_order FROM menus WHERE path = '/admin/ceo/accounts');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '수신 상품', '/admin/ceo/banking', '🏦',
       '정기예금 · 적금 · 퇴직연금', 'CEO', 'ITEM',
       a.sort_order + 1, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
CROSS JOIN menus a
WHERE p.name = 'CEO'
  AND p.parent_id IS NULL
  AND a.path = '/admin/ceo/accounts'
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/ceo/banking');
