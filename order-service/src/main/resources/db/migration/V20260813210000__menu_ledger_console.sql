-- ============================================================
-- V20260813210000 : 정산운영 그룹에 '원장·시산표' 화면 등록
--
-- 역할을 ADMIN,MANAGER 로 두는 이유: 이 화면의 본체인 분개 조회(/api/ledger/**)가
-- ADMIN·MANAGER 표면이다. 시산표·기간 마감(/admin/ledger-periods/**)만 ADMIN 전용이라
-- 화면 안에서 역할로 가른다. 메뉴를 ADMIN 으로 좁히면 MANAGER 가 분개 조회조차 못 한다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '원장·시산표', '/admin/settlement/ledger', '📒',
       '분개 조회 · 월 시산표 · 기간 마감', 'BACKOFFICE', 'ITEM', 1, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL;
