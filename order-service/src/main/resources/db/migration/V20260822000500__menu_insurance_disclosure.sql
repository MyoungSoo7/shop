-- ============================================================
-- V20260822000500 : '상품설명서 교부' 화면을 시스템 관리 메뉴에 등록
--
-- 청약 승인은 완전판매 게이트를 통과해야 한다 — 교부 증빙이 없으면 서버가 409 로 거절한다
-- (DisclosureNotDeliveredException). 교부는 API 로만 가능했으므로, 화면이 없는 동안 승인을
-- UI 경로로 통과시킬 방법이 아예 없었다.
--
-- 시스템 관리 아래인 이유: 보험 표면이 이미 여기 있다(증빙 리뷰 큐, ADR 0036).
-- 서버는 authenticated 만 요구하고 교부자를 JWT 에서 파생하지만, 우리 앱에는 FC 역할이 없어
-- ADMIN 으로 맞춘다 — MANAGER 에게 열면 서버는 통과시키는데 메뉴 정책만 어긋난다.
-- FC 역할이 생기면 이 행의 required_role 부터 재검토한다.
--
-- 정렬은 '교육 관리' 뒤. 증빙 리뷰 큐 옆이 아니라 여기인 것은 성격이 달라서다 —
-- 리뷰 큐는 이미 올라온 서류를 '판정'하고, 이 화면은 문서를 '발급'한다.
--
-- 기준 형제는 서브쿼리가 아니라 조인으로 가져온다 — 서브쿼리면 그 경로 리터럴이 SELECT 절에
-- 남아 menu-route-gate 의 시드 추출기가 '새로 심는 메뉴'로 센다(조건절 줄만 걸러내는 추출기다).
-- ============================================================

UPDATE menus
   SET sort_order = sort_order + 1, updated_at = NOW()
 WHERE parent_id = (SELECT id FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL)
   AND sort_order > (SELECT sort_order FROM menus WHERE path = '/admin/system/education');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '상품설명서 교부', '/admin/system/insurance-disclosures', '📜',
       '보험 상품설명서 미리보기 · 교부 증빙', 'SYSTEM', 'ITEM',
       e.sort_order + 1, 'ADMIN', TRUE, TRUE
FROM menus p
CROSS JOIN menus e
WHERE p.name = '시스템 관리'
  AND p.parent_id IS NULL
  AND e.path = '/admin/system/education'
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/insurance-disclosures');
