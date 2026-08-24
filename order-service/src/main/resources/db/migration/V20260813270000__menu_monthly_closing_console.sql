-- ============================================================
-- V20260813270000 : 정산운영 그룹에 '월마감' 화면 등록
--
-- 차지백과 마찬가지로 ADMIN 전용이다 — 서버가 /admin/monthly-closing/** 를 ADMIN 으로 막는다
-- (마감은 확정 장부를 만드는 실행이라). 원장·시산표 바로 앞에 둔다: 월 집계를 확정한 뒤
-- 그 근거 분개를 보러 가는 흐름이다.
-- ============================================================

UPDATE menus SET sort_order = 6
WHERE path = '/admin/settlement/ledger'
  AND parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '월마감', '/admin/settlement/monthly-closing', '📆',
       '셀러 월 정산 마트 집계 · 재실행', 'BACKOFFICE', 'ITEM', 5, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL;
