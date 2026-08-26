-- ============================================================
-- V20260827150000 : 시스템 관리 그룹에 '팝업 관리' 등록
--
--   · 팝업 관리  /admin/system/site-popups  — 사이트 팝업 노출 구간 · 순서
--
-- 자리·URL·권한의 근거는 V20260827110000(수강 신청)·V20260827130000(강사 관리)과 같다:
--   ① sort_order 는 그룹 맨 뒤 — 중간에 끼우면 그 자리 항목과 겹친다.
--   ② 화면 URL(/admin/system/...)은 API 경로(/api/ops/popups)와 달라야 한다. nginx SPA 폴백이
--      /admin/(system|operation|shipping|approvals|login) 만 index.html 로 내려보내기 때문에,
--      같게 두면 새로고침 때 API JSON 이 브라우저에 그대로 렌더된다.
--   ③ required_role 은 OperationSecurityConfig(@Order(1)) 가 /api/ops/** 에 실제로 요구하는
--      ADMIN 을 그대로 옮긴다 — 메뉴가 보여 주는 권한과 서버가 막는 권한이 갈리면 안 된다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '팝업 관리', '/admin/system/site-popups', '🪟',
       '사이트 팝업 노출 구간 · 순서', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/site-popups');
