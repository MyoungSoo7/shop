-- ============================================================
-- V20260827130000 : 시스템 관리 그룹에 '강사 관리' 등록
--
--   · 강사 관리  /admin/system/education-lecturers  — 강사 명부 · 전공/강의 분야 · 과정 배정
--
-- 자리·URL·권한의 근거는 V20260827110000(수강 신청)과 같다. 요약하면:
--   ① sort_order 는 그룹 맨 뒤로 잡는다 — 중간에 끼우면 그 자리 항목과 겹친다.
--   ② 화면 URL(/admin/system/...)은 API 경로(/admin/education/lecturers)와 달라야 한다.
--      nginx SPA 폴백이 /admin/(system|operation|shipping|approvals|login) 만 index.html 로
--      내려보내고, 같게 두면 새로고침 때 API JSON 이 브라우저에 그대로 렌더된다.
--   ③ required_role 은 EducationSecurityConfig(@Order(4)) 가 실제로 막는 ADMIN 을 그대로 옮긴다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '강사 관리', '/admin/system/education-lecturers', '🎓',
       '강사 명부 · 전공/강의 분야 · 과정 배정', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/education-lecturers');
