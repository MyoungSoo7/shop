-- ============================================================
-- V20260813230000 : 정산운영 그룹에 '일일 대사' 화면 등록
--
-- 서버가 /admin/reconciliation/** 를 ADMIN·MANAGER 로 게이트하므로 메뉴도 같은 등급이다.
-- 정합성 검증(판정 8종) 다음, 원장(장부 조회) 앞에 두어 "깨졌나 → 어디가 → 무슨 분개" 순서로
-- 읽히게 배치한다.
-- ============================================================

UPDATE menus SET sort_order = 2
WHERE path = '/admin/settlement/ledger'
  AND parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '일일 대사', '/admin/settlement/reconciliation', '🔀',
       'order ↔ settlement 이중장부', 'BACKOFFICE', 'ITEM', 1, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL;
