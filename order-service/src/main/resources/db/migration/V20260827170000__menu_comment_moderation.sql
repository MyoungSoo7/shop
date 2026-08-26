-- ============================================================
-- V20260827170000 : 시스템 관리 그룹에 '댓글 관리' 등록
--
--   · 댓글 관리  /admin/system/comment-moderation  — 전 게시판 댓글 · 신고 판정
--
-- '게시판 관리'(V20260815100000) 와 별개의 항목인 이유: 게시판 관리는 게시판 *정의* 를 만드는
-- 자리이고, 여기는 이미 달린 *댓글* 을 게시판·글을 건너뛰고 훑는 자리다. 기존 댓글 API 는 전부
-- /api/boards/{key}/posts/{postId}/comments 아래 있어, 문제 댓글을 내리려면 그 댓글이 어느 글에
-- 달렸는지를 관리자가 먼저 알아내야 했다.
--
-- 자리·URL·권한의 근거는 V20260827110000(수강 신청)과 같다. 요약하면:
--   ① sort_order 는 그룹 맨 뒤로 잡는다 — 중간에 끼우면 그 자리 항목과 겹친다.
--   ② 화면 URL(/admin/system/...)은 API 경로(/admin/boards/comments)와 달라야 한다.
--      nginx SPA 폴백이 /admin/(system|operation|shipping|approvals|login) 만 index.html 로
--      내려보내고, 같게 두면 새로고침 때 API JSON 이 브라우저에 그대로 렌더된다.
--   ③ required_role 은 BoardSecurityConfig(@Order(3)) 가 /admin/boards/** 에 실제로 거는
--      ADMIN 을 그대로 옮긴다. 넓히면 눌러서 403 을 받는 링크가 된다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '댓글 관리', '/admin/system/comment-moderation', '💬',
       '전 게시판 댓글 · 신고 판정 · 가림', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/comment-moderation');
