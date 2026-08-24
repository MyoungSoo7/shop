-- ============================================================
-- V20260814110000 : 정산운영 그룹에 '수수료율' 화면 등록
--
-- 차지백·월마감과 같은 ADMIN 전용이다 — 서버가 /admin/commission-rates/** 를 ADMIN 으로만 막는다.
-- 요율은 정산 금액을 직접 바꾸므로 조회 콘솔과 달리 MANAGER 에게 열지 않는다(ADR 0032).
--
-- 세무 다음, 원장·시산표 앞에 둔다: 요율(입력) → 세무(산출물) → 원장(기록) 순으로 읽힌다.
-- ============================================================

UPDATE menus SET sort_order = 8
WHERE path = '/admin/settlement/ledger'
  AND parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '수수료율', '/admin/settlement/commission-rates', '⚖️',
       '셀러·등급 요율 정책 · 이력', 'BACKOFFICE', 'ITEM', 7, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL;
