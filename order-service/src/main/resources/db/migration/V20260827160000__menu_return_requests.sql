-- ============================================================
-- V20260827160000 : 반품·교환 처리 콘솔 메뉴 등록
--
--   · 반품·교환  /admin/approvals/returns  — 승인 → 회수 확인 → 환불/재배송
--
-- 버전이 160000 인 이유: Flyway 는 버전이 겹치면 스키마 비교 이전에 통째로 죽는데, 같은 날
-- 100000(반품 신청 테이블) · 110000(수강 신청 메뉴) · 130000(강사 메뉴) · 150000(팝업 메뉴)
-- 이 이미 차 있다. 뒤의 셋은 다른 세션이 병렬로 올린 것이라 파일명이 달라 git 은 충돌로 보지
-- 않는다 — 겹친 버전은 머지된 뒤 부팅에서야 드러난다. 새 메뉴 마이그레이션을 만들 때는
-- 커밋 전에 이 디렉터리의 같은 날짜 버전을 반드시 눈으로 확인할 것.
--
-- 왜 '승인'(/admin/approvals) 아래가 아니라 옆인가: '승인' 은 GROUP 이 아니라 ITEM 이다.
-- 자식을 붙이려면 그 행을 GROUP 으로 바꿔야 하는데, 그러면 지금 그 링크를 눌러 들어가는
-- 취소·환불 승인 큐가 링크가 아니게 된다(GROUP 은 펼침일 뿐 자기 화면이 없다).
-- 그래서 형제 ITEM 으로 둔다 — 두 화면은 실제로 하는 일이 다르다.
--
-- 화면 URL 이 /admin/return-requests 가 아닌 이유는 종전과 같다. ① 그 경로는 이 화면이 부르는
-- API 이고, 화면 URL 을 같게 두면 새로고침 때 목록 JSON 이 그대로 렌더된다. ② nginx SPA 폴백은
-- /admin/(system|operation|shipping|approvals|login) 만 index.html 로 내려보내므로 다른 접두사는
-- 새로고침에서 404 가 된다(vite dev 에서는 보이지 않는다). approvals 접두사 아래면 둘 다 피한다.
--
-- required_role 은 서버가 실제로 막는 등급을 그대로 옮긴다 — SecurityConfig 의
-- /admin/return-requests/** 매처가 ADMIN·MANAGER 다. 반품 응대는 CS 업무라 MANAGER 가 처리한다.
--
-- 자리는 '승인' 바로 뒤다. MAX+1 로 맨 뒤에 붙이면 ADMIN 상단 네비에서 '시스템' 뒤로 가는데,
-- 그러면 DB 순서와 프론트 폴백(menuFallback.ts) 순서가 서로 달라진다 — 폴백은 서버가 죽었을 때
-- 같은 화면을 그리는 사본이므로, 순서가 갈리면 그 사실이 장애 중에야 드러난다.
-- 그래서 뒤 항목들을 한 칸씩 밀고 '승인'+1 자리에 끼운다. 두 문장 다 멱등이라야 해서
-- 밀기는 "아직 그 메뉴가 없을 때만" 돈다(있으면 이미 한 번 밀린 상태다).
-- ============================================================

UPDATE menus
SET sort_order = sort_order + 1
WHERE parent_id IS NULL
  AND sort_order > (SELECT m.sort_order FROM menus m WHERE m.path = '/admin/approvals')
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/approvals/returns');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT NULL, '반품·교환', '/admin/approvals/returns', '🔁',
       '승인 · 회수 확인 · 환불 · 교환 재배송', 'BACKOFFICE', 'ITEM',
       -- 조건절 경로는 줄머리에 WHERE 를 두어 한 줄로 세운다. menu-route-gate 는 줄머리가
       -- WHERE/AND/OR 인 줄을 "행을 고르는 조건"으로 읽고 세지 않는다 — 여기의 /admin/approvals
       -- 는 새로 심는 메뉴가 아니라 자리를 계산하려고 짚는 기존 행이므로 세면 중복이 된다.
       (SELECT m.sort_order + 1 FROM menus m
        WHERE m.path = '/admin/approvals'),
       'ADMIN,MANAGER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/approvals/returns');
