-- ============================================================
-- V20260813250000 : 정산운영 그룹에 '차지백' 화면 등록
--
-- 다른 운영 화면과 달리 역할이 'ADMIN' 인 이유: 서버가 /admin/chargebacks/** 를 ADMIN 전용으로
-- 막는다(수락하면 셀러 정산금에서 차감되는 결정이라). MANAGER 에게 메뉴를 보여 주면 눌러도
-- 403 인 죽은 링크가 되므로 메뉴 등급을 서버 게이트에 맞춘다.
-- ============================================================

UPDATE menus SET sort_order = 4
WHERE path = '/admin/settlement/ledger'
  AND parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '차지백', '/admin/settlement/chargebacks', '⚖️',
       '카드사 분쟁 수락 · 기각', 'BACKOFFICE', 'ITEM', 3, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL;
