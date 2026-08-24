-- ============================================================
-- V20260822012000 : '보험 영업' 화면을 시스템 관리 메뉴에 등록
--
-- 보험 영업 체인(가입설계 → 청약 → 승인 → 계약)이 백엔드에만 있었다. 어제 붙인 상품설명서
-- 교부 화면이 여는 '승인'이 바로 이 체인의 한 단계인데, 그 승인을 누를 화면이 없어 교부 증빙만
-- 남기고 끝나 있었다.
--
-- 정렬은 '상품설명서 교부' 바로 뒤 — 승인이 그 교부 증빙을 요구하므로 두 화면을 오가게 된다.
--
-- ADMIN 전용: 서버는 authenticated 만 요구하고 fcId 를 JWT 에서 파생하지만, 우리 앱에 FC 역할이
-- 없어 교부 화면과 같은 등급으로 맞춘다. FC 역할이 생기면 두 화면을 함께 재검토한다.
--
-- 기준 형제는 서브쿼리가 아니라 조인으로 가져온다 — 서브쿼리면 그 경로 리터럴이 SELECT 절에
-- 남아 menu-route-gate 의 시드 추출기가 '새로 심는 메뉴'로 센다.
-- ============================================================

UPDATE menus
   SET sort_order = sort_order + 1, updated_at = NOW()
 WHERE parent_id = (SELECT id FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL)
   AND sort_order > (SELECT sort_order FROM menus WHERE path = '/admin/system/insurance-disclosures');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '보험 영업', '/admin/system/insurance-sales', '📝',
       '가입설계 · 청약 · 승인 · 계약', 'SYSTEM', 'ITEM',
       d.sort_order + 1, 'ADMIN', TRUE, TRUE
FROM menus p
CROSS JOIN menus d
WHERE p.name = '시스템 관리'
  AND p.parent_id IS NULL
  AND d.path = '/admin/system/insurance-disclosures'
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/insurance-sales');
