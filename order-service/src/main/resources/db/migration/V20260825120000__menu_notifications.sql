-- 내 알림(실시간 푸시 SSE) 화면 메뉴 시드.
--
-- 네비게이션 정본은 menus 테이블이다(프론트 셸은 GET /api/menus/me 로 그린다). 라우트만 만들고
-- 이 행을 넣지 않으면 화면은 존재하는데 아무도 도달할 수 없다 — menu-route-gate 가 그 상태를 막는다.
--
-- 이 화면이 늦게 생긴 이유를 남긴다: 알림 푸시 SSE 는 백엔드(구독·팬아웃·재생 창)와 프론트
-- API 클라이언트(src/api/notificationStream.ts)·계약 테스트까지 다 있었는데 **그것을 렌더하는
-- 컴포넌트가 하나도 없었다**. EventSource 를 쓰는 비테스트 파일이 marketStream·notificationStream
-- 두 API 클라이언트뿐이라, 스트림은 살아 있고 게이트도 초록인데 사용자에게는 도달하지 않는
-- 상태였다(2026-08-25 실측, ADR 0041 후속 KI-8).
--
-- SHOP 영역 최상위, 내 포인트·상품권(30) 다음에 둔다 — 둘 다 "내 것"을 보는 개인 화면이다.
-- required_role 은 USER: 수신자 신원은 서버가 JWT 에서만 파생하므로(sub·uid, ADMIN 은 ops 메일함
-- 추가) 역할로 더 좁힐 이유가 없다. 좁히면 셀러가 자기 정산 확정 알림을 못 본다.

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type, sort_order, required_role, visible, active)
SELECT NULL, '내 알림', '/notifications', '🔔', '정산·결제·투자 체결 실시간 알림', 'SHOP', 'ITEM', 35, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/notifications');
