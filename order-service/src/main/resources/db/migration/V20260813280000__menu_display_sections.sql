-- ============================================================
-- V20260813280000 : 시스템 관리 그룹에 '진열 편성' 화면 등록
--
-- Phase 7(display_sections)은 테이블·API 까지 서 있었지만 진입점이 없어 운영자가 편성을 만들 수
-- 없었다. 카테고리(분류) 바로 뒤에 둔다 — 분류는 상품의 성질, 진열은 기간이 있는 편성이라
-- "무엇으로 묶이나 → 언제 앞에 세우나" 순으로 읽힌다.
--
-- 서버가 /admin/display-sections/** 를 ADMIN 으로 게이트(@PreAuthorize)하므로 메뉴도 ADMIN 이다.
-- ============================================================

UPDATE menus SET sort_order = 5
WHERE path = '/admin/system/operation'
  AND parent_id = (SELECT id FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '진열 편성', '/admin/system/display-sections', '🎪',
       '기획전 · 메인 진열 편성', 'SYSTEM', 'ITEM', 4, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL;
