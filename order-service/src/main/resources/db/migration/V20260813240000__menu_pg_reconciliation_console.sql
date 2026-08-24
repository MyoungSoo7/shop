-- ============================================================
-- V20260813240000 : 정산운영 그룹에 'PG 대사' 화면 등록
--
-- 서버가 /admin/pg-reconciliation/** 를 ADMIN·MANAGER 로 게이트하므로 메뉴도 같은 등급이다.
-- 일일 대사(내부 이중장부) 바로 뒤에 둔다 — 둘 다 "장부가 맞나"를 보는 화면이고,
-- 내부 대사가 맞아도 PG 파일과 어긋날 수 있어 순서대로 읽히는 편이 자연스럽다.
-- ============================================================

UPDATE menus SET sort_order = 3
WHERE path = '/admin/settlement/ledger'
  AND parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, 'PG 대사', '/admin/settlement/pg-reconciliation', '🧾',
       'PG 정산파일 업로드 · 차이 승인 · 마감', 'BACKOFFICE', 'ITEM', 2, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL;
