-- ============================================================
-- V20260827110000 : 시스템 관리 그룹에 '수강 신청' 등록
--
--   · 수강 신청  /admin/system/education-enrollments  — 과정별 신청자 · 정원 · 대기 · 취소
--
-- 왜 '교육 관리' 옆이 아니라 그룹 맨 뒤인가: 중간에 끼우려면 뒤 항목의 sort_order 를 통째로
-- 밀어야 하는데 이 그룹은 빈틈없이 차 있어 한 항목만 옮기면 그 자리의 항목과 겹친다
-- (V20260820100000 참조). 자리보다 겹침이 더 비싸다.
--
-- 화면 URL 이 API 경로(/admin/education/enrollments)와 다른 이유는 종전과 같다.
-- ① nginx SPA 폴백은 /admin/(system|operation|shipping|approvals|login) 만 index.html 로
-- 내려보내므로 다른 접두사는 새로고침에서 404 가 된다(vite dev 에서는 안 보인다).
-- ② 화면 URL 을 API 와 같게 두면 새로고침 때 API JSON 이 그대로 브라우저에 렌더된다.
--
-- required_role 은 서버가 실제로 막는 등급을 그대로 옮긴다 — EducationSecurityConfig(@Order(4))
-- 가 /admin/education/** 을 ADMIN 으로 막으므로 여기도 ADMIN 이다. 넓히면 눌러서 403 을 받는
-- 링크가 되고, 좁히면 권한 있는 사람이 화면을 못 찾는다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '수강 신청', '/admin/system/education-enrollments', '📝',
       '과정별 신청자 · 정원 · 대기 · 취소', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/education-enrollments');
