-- ============================================================
-- V20260820190000 : '배송'을 그룹으로 바꾸고 '배송비 정책' 화면을 등록
--
-- 배송비 정책(셀러 기본배송비·무료배송 임계)은 고객이 실제로 지불하는 금액을 정하는데,
-- 지금까지 진입점이 없어 DB 로만 넣을 수 있었다. 그러면 배송비 계산은 코드로만 존재하는
-- 기능이 된다 — 정책이 없는 셀러의 기본배송비는 0 원으로 계산되어 조용히 무료배송이 된다.
--
-- '배송'은 단일 ITEM 이었다. 화면이 둘이 되었으므로 GROUP 으로 올리고 그 아래로 내린다.
-- 상단 네비는 최상위 노드만 그리고 하위는 SideNavLayout 이 그리므로, GROUP 으로 올리지 않으면
-- 새 화면으로 갈 길이 아예 없다(라우트만 있고 진입점이 없는 유령 화면).
--
-- GROUP 자신도 path 를 갖고 자식과 같은 경로를 공유한다 — '정산'(/admin/settlement)과
-- 그 자식 '정산관리'가 이미 같은 방식이다.
--
-- required_role 이 자식마다 다른 이유: 서버가 /admin/shipping-policies/** 를 ADMIN 으로 막는다
-- (SecurityConfig). MANAGER 에게 보여 주면 눌러도 되돌려보내지는 죽은 링크가 된다.
-- ============================================================

UPDATE menus
   SET menu_type = 'GROUP'
 WHERE name = '배송'
   AND parent_id IS NULL;

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, v.name, v.path, v.icon, v.description, 'BACKOFFICE', 'ITEM',
       v.sort_order, v.required_role, TRUE, TRUE
FROM menus p
CROSS JOIN (VALUES
    ('배송 관리',   '/admin/shipping',          '🚚', '주문별 배송 생성 · 출고 · 상태 전이', 0, 'ADMIN,MANAGER'),
    ('배송비 정책', '/admin/shipping-policies', '💵', '셀러 기본배송비 · 무료배송 임계',     1, 'ADMIN')
) AS v(name, path, icon, description, sort_order, required_role)
WHERE p.name = '배송'
  AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/shipping-policies');
