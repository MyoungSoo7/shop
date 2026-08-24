-- ============================================================
-- V20260814100000 : 정산운영 그룹에 '세무' 화면 등록
--
-- 서버가 /admin/tax/** 와 /admin/seller-tax-profiles/** 를 ADMIN·MANAGER 로 막으므로 메뉴도 같은
-- 등급으로 연다(차지백·월마감처럼 ADMIN 으로 좁히면 MANAGER 가 스캔 리뷰조차 못 한다).
--
-- 월마감 다음, 원장·시산표 앞에 둔다: 세무 전표를 전기한 뒤 그 분개를 원장에서 확인하는 흐름이라
-- 순서 자체가 작업 순서를 알려 준다.
-- ============================================================

UPDATE menus SET sort_order = 7
WHERE path = '/admin/settlement/ledger'
  AND parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '세무', '/admin/settlement/tax', '🧾',
       '스캔 리뷰 · 전표 전기 · 세금계산서', 'BACKOFFICE', 'ITEM', 6, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL;
