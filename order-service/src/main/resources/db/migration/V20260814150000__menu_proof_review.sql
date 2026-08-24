-- ============================================================
-- V20260814150000 : 시스템 관리 그룹에 '증빙 리뷰 큐' 화면 등록
--
-- ADR 0036 증빙 OCR 이 확신하지 못한 서류(NEEDS_REVIEW — 카드 영수증·보험 청약서류·대출 담보서류·
-- 예치금 증빙)를 한 화면에서 육안 종결하는 콘솔이다. 리뷰 확정은 각 서비스의 승인·기표 게이트를
-- 여는 운영 판단이라 서버 4곳 전부 ADMIN(또는 ADMIN/MANAGER) 게이트이고, 메뉴도 ADMIN 이다.
-- 운영관리(6) 뒤에 둔다 — 관제 다음의 사후 판정 콘솔.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '증빙 리뷰 큐', '/admin/system/proof-review', '🧾',
       '증빙 OCR 리뷰 큐 (영수증·청약·담보·예치금)', 'SYSTEM', 'ITEM', 7, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL;
