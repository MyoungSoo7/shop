-- ============================================================
-- V20260821230000 : 새로고침하면 깨지던 화면 3개의 메뉴 경로를 옮긴다
--
-- nginx 는 /admin 하위에서 <b>네비 그룹 접두사</b>만 index.html 로 폴백하고, 나머지는 전부
-- 게이트웨이로 프록시한다. 아래 세 화면은 그 목록 밖에 있었고, 게다가 <b>백엔드 API 와 URL 이
-- 정확히 같았다</b>. 그래서 클릭 이동은 멀쩡한데 새로고침·북마크·새 탭에서는 화면 대신
-- API 응답(JSON 또는 401)이 브라우저에 그대로 렌더됐다.
--
--   /admin/payouts           → /admin/settlement/payouts   (settlement API 와 충돌)
--   /admin/shipping-policies → /admin/shipping/policies    (order API 와 충돌)
--   /admin/education/courses → /admin/system/education     (education API 와 충돌)
--
-- 폴백 목록에 이 이름들을 더하는 것은 오답이다 — 그러면 이번엔 프론트가 같은 URL 의 API 를
-- 못 부른다(index.html 이 응답된다). 그래서 <b>API 가 아니라 화면 URL 을 옮긴다</b>.
-- API 경로는 하나도 바뀌지 않는다.
--
-- 나머지 두 건(/admin/shipping·/admin/approvals)은 API 와 겹치지 않는 <b>네비 그룹</b>이라
-- nginx 폴백 목록에 그룹으로 등록했다 — 경로 변경 없음. 규칙은 spa-fallback-gate 가 강제한다.
--
-- DELETE+INSERT 가 아니라 UPDATE 인 이유: 같은 메뉴가 자리를 옮기는 것이지 다른 메뉴가
-- 생기는 것이 아니다. id 를 보존해야 자식·운영 화면에서의 참조가 끊기지 않는다.
-- (menu-route-gate 의 추출기가 이 UPDATE 를 경로 이동으로 읽도록 함께 고쳤다.)
--
-- 세 문장 모두 대상이 없으면 0행 갱신으로 조용히 지나간다 — 재실행에 안전하다.
-- ============================================================

UPDATE menus SET path = '/admin/settlement/payouts', updated_at = NOW()
 WHERE path = '/admin/payouts';

UPDATE menus SET path = '/admin/shipping/policies', updated_at = NOW()
 WHERE path = '/admin/shipping-policies';

UPDATE menus SET path = '/admin/system/education', updated_at = NOW()
 WHERE path = '/admin/education/courses';
