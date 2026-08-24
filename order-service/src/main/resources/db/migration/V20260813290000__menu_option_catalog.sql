-- ============================================================
-- V20260813290000 : 시스템 관리 그룹에 '옵션 카탈로그' 화면 등록
--
-- 표준 축·값은 그동안 시드 마이그레이션과 레거시 백필로만 늘릴 수 있었다 — 축 하나 추가에 배포가
-- 필요했다는 뜻이다. 카테고리(분류)·진열 편성 다음에 둔다: 무엇으로 묶이나 → 언제 앞에 세우나 →
-- 무엇으로 고르나.
--
-- 서버가 /admin/option-catalog/** 를 ADMIN 으로 게이트(@PreAuthorize)하므로 메뉴도 ADMIN 이다.
-- ============================================================

UPDATE menus SET sort_order = 6
WHERE path = '/admin/system/operation'
  AND parent_id = (SELECT id FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '옵션 카탈로그', '/admin/system/option-catalog', '🎛️',
       '표준 옵션 축 · 값 관리', 'SYSTEM', 'ITEM', 5, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL;
