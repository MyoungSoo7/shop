-- ============================================================
-- V20260828100000 : 문의 화면 메뉴 등록
--
--   · 내 문의     /my/inquiries              — 사용자가 자기 문의를 보고 고치고 철회한다 (SHOP)
--   · 문의 응대   /admin/approvals/inquiries — 대기열에서 답변을 단다 (BACKOFFICE)
--
-- 버전이 0828 인 이유: 같은 날(0827)의 100000·110000·130000·150000·160000·170000·180000·
-- 200000·210000·220000·230000 이 이미 차 있다. Flyway 는 버전이 겹치면 스키마 비교 이전에
-- 통째로 죽고, 병렬 세션이 올린 파일은 이름이 달라 git 이 충돌로 보지 않는다 — 겹친 버전은
-- 머지된 뒤 부팅에서야 드러난다. 문의 테이블(V20260828090000__inquiries.sql) 바로 뒤에 둔다.
--
-- 사용자 화면 URL 이 /inquiries 가 아닌 이유: 그건 이 화면이 부르는 API 경로이고, nginx 두 벌이
-- 방금 inquiries 세그먼트를 게이트웨이로 프록시하게 되었다. 같은 URL 을 화면에 쓰면 새로고침에서
-- 목록 JSON 이 그대로 브라우저에 렌더된다. 이미 같은 이유로 /my/balances 가 그 자리에 있다.
--
-- 관리자 화면이 '승인'(/admin/approvals) 의 자식이 아니라 형제인 이유는 반품·교환과 같다:
-- '승인' 은 GROUP 이 아니라 ITEM 이라, 자식을 붙이려면 그 행을 GROUP 으로 바꿔야 하고 그러면
-- 지금 그 링크로 들어가는 취소·환불 승인 큐가 링크가 아니게 된다. 그리고 URL 이 approvals
-- 접두사 아래인 이유도 같다 — nginx SPA 폴백이 /admin/(system|operation|shipping|approvals|login)
-- 만 index.html 로 내려보내므로 다른 접두사는 새로고침에서 404 다(vite dev 에서는 안 보인다).
--
-- required_role 은 서버가 실제로 막는 등급을 그대로 옮긴다 — SecurityConfig 의
-- /admin/inquiries/** 매처가 ADMIN·MANAGER 다. 문의 응대는 CS 업무라 MANAGER 가 처리한다.
-- 사용자 표면(/inquiries/**)에는 매처가 없다. anyRequest().authenticated() 로 떨어지고
-- "누구의 것인가"는 서비스가 소유권 대조로 정하기 때문이다.
--
-- 자리는 '반품·교환' 바로 뒤다. MAX+1 로 맨 뒤에 붙이면 ADMIN 상단 네비에서 '시스템' 뒤로 가고,
-- 그러면 DB 순서와 프론트 폴백(menuFallback.ts) 순서가 갈린다 — 폴백은 서버가 죽었을 때 같은
-- 화면을 그리는 사본이라, 순서가 갈리면 그 사실이 장애 중에야 드러난다. 그래서 뒤 항목들을
-- 한 칸씩 밀고 그 자리에 끼운다. 밀기는 "아직 그 메뉴가 없을 때만" 돌아야 멱등이다.
-- ============================================================

UPDATE menus
SET sort_order = sort_order + 1
-- area 로 좁히지 않는다. '시스템 관리' 는 area 가 SYSTEM 이라 BACKOFFICE 로 걸러 내면 밀리지
-- 않고, 그러면 '문의 응대' 가 그 행과 같은 sort_order 를 받아 둘의 순서가 정해지지 않는다.
-- 최상위 전체를 미는 것은 앞선 반품·교환 마이그레이션과 같은 방식이며 상대 순서는 보존된다.
WHERE parent_id IS NULL
  AND sort_order > (SELECT m.sort_order FROM menus m WHERE m.path = '/admin/approvals/returns')
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/approvals/inquiries');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT NULL, '문의 응대', '/admin/approvals/inquiries', '💬',
       '상품 문의 · 주문 문의 · 1:1 문의 답변', 'BACKOFFICE', 'ITEM',
       -- 조건절 경로는 줄머리에 WHERE 를 두어 한 줄로 세운다. menu-route-gate 는 줄머리가
       -- WHERE/AND/OR 인 줄을 "행을 고르는 조건"으로 읽고 세지 않는다 — 여기의 반품·교환 경로는
       -- 새로 심는 메뉴가 아니라 자리를 계산하려고 짚는 기존 행이므로 세면 중복이 된다.
       (SELECT m.sort_order + 1 FROM menus m
        WHERE m.path = '/admin/approvals/returns'),
       'ADMIN,MANAGER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/approvals/inquiries');

-- 사용자 화면 — SHOP 영역의 sort_order 는 7·8·20·25·30·35 로 차 있어 뒤에 40 으로 붙인다.
INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT NULL, '내 문의', '/my/inquiries', '💬',
       '상품 문의 · 주문 문의 · 1:1 문의', 'SHOP', 'ITEM', 40, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/my/inquiries');
