-- ============================================================
-- V20260814160000 : CEO 하위 '법인카드' 메뉴 (card-service 콘솔)
--
-- card-service 의 카드계정·임직원 카드 표면(/api/cards/**)을 처음으로 화면에 노출한다.
-- CEO 영역에 두는 이유: 법인카드는 셀러 조직(법인)의 여신 기능이라, 대출관리·계정계 현황과
-- 같은 기업 재무 묶음이다. 서버 인가는 조직 멤버십(OWNER/MANAGER/STAFF)으로 별도 판정하므로
-- required_role 은 셸 노출 기준(ADMIN,MANAGER — 다른 CEO 항목과 동일)일 뿐이다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '법인카드', '/admin/ceo/cards', '💳', '카드계정 · 임직원 카드 한도', 'CEO', 'ITEM',
       13, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = 'CEO' AND p.parent_id IS NULL;
