-- ============================================================
-- V20260822002500 : '조직 · 멤버십' 화면을 시스템 관리 메뉴에 등록
--
-- organization-service 는 화면이 하나도 없었다. 조직을 만들고 사람을 붙이는 경로가 API 뿐이라
-- 셀러/기업 조직 구조를 실질적으로 운영할 수 없었다(부채 성격상 '기능은 있는데 못 쓴다').
--
-- 회원 관리 바로 뒤인 이유: 사람을 다루는 축이 같다. 개인(회원)을 본 다음 그 사람이 속한
-- 조직으로 이어지는 순서다.
--
-- ADMIN 전용: 서버는 authenticated 만 요구하고 조직 내 역할(OrgAuthorizer)로 인가하지만,
-- 우리 앱의 SYSTEM 영역은 ADMIN 관례라 그에 맞춘다. 조직 소유자 셀프서비스 화면이 생기면
-- 그때 별도 경로로 분리한다.
--
-- 기준 형제는 서브쿼리가 아니라 조인으로 가져온다 — 서브쿼리면 그 경로 리터럴이 SELECT 절에
-- 남아 menu-route-gate 의 시드 추출기가 '새로 심는 메뉴'로 센다(조건절 줄만 걸러내는 추출기다).
-- ============================================================

UPDATE menus
   SET sort_order = sort_order + 1, updated_at = NOW()
 WHERE parent_id = (SELECT id FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL)
   AND sort_order > (SELECT sort_order FROM menus WHERE path = '/admin/system/members');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '조직 · 멤버십', '/admin/system/organizations', '🏢',
       '셀러/기업 조직 · 초대 · 역할', 'SYSTEM', 'ITEM',
       m.sort_order + 1, 'ADMIN', TRUE, TRUE
FROM menus p
CROSS JOIN menus m
WHERE p.name = '시스템 관리'
  AND p.parent_id IS NULL
  AND m.path = '/admin/system/members'
  AND NOT EXISTS (SELECT 1 FROM menus x WHERE x.path = '/admin/system/organizations');
