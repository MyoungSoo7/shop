-- ============================================================
-- V20260813260000 : 정산운영 그룹에 '회수 채권' 화면 등록
--
-- 서버가 /admin/recoveries/** 를 ADMIN·MANAGER 로 게이트하므로 메뉴도 같은 등급이다.
-- 차지백 바로 뒤에 둔다 — 차지백 수락이 만든 마이너스 조정이 곧 이 화면의 채권이 되므로,
-- 결정(차지백) → 결과(회수) 순으로 읽히는 편이 자연스럽다.
-- ============================================================

UPDATE menus SET sort_order = 5
WHERE path = '/admin/settlement/ledger'
  AND parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '회수 채권', '/admin/settlement/recoveries', '🧲',
       '셀러별 미상계 잔액 · 상계 이력', 'BACKOFFICE', 'ITEM', 4, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL;
