-- ============================================================
-- V20260827210000 : 시스템 관리 그룹에 '동의 이력' 등록
--
--   · 동의 이력  /admin/system/privacy-consents  — 주문 시점 개인정보 동의 · 문안 버전별 조회
--
-- V20260827200000 이 만든 동의 기록을 <b>읽는</b> 진입점이다. 기록만 남기고 볼 경로가 없으면
-- 정보주체의 열람 요구에 답할 수도, 문안을 고친 뒤 옛 버전 동의자가 남았는지 셀 수도 없다.
--
-- 자리·URL·권한의 근거는 V20260827130000(강사 관리)과 같다. 요약하면:
--   ① sort_order 는 그룹 맨 뒤로 잡는다 — 중간에 끼우면 그 자리 항목과 겹친다.
--   ② 화면 URL(/admin/system/privacy-consents)은 API 경로(/admin/privacy-consents)와 달라야 한다.
--      nginx SPA 폴백이 /admin/(system|operation|shipping|approvals|login) 만 index.html 로
--      내려보내고, 같게 두면 새로고침 때 동의 이력 JSON 이 브라우저에 그대로 렌더된다.
--   ③ required_role 은 SecurityConfig 의 `/admin/privacy-consents` 매처가 실제로 막는
--      ADMIN·MANAGER 를 그대로 옮긴다. 읽기 전용(고치는 경로가 아예 없다)이라 MANAGER 도 연다.
--      메뉴만 좁히면 권한 있는 사람이 화면을 못 찾고, 넓히면 눌러서 403 을 받는 링크가 된다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '동의 이력', '/admin/system/privacy-consents', '📝',
       '주문 시점 개인정보 동의 · 문안 버전별 조회', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/privacy-consents');
