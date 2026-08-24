-- ============================================================
-- V20260822004000 : '환불 운영' 화면을 정산운영 메뉴에 등록
--
-- 자동 재시도는 5회까지다(백오프 1·5·15·60·180분). 상한에 도달하면 next_retry_at 이 비워져
-- RefundRetryScheduler 가 그 건을 더는 집지 않는다 — 그 순간부터 사람이 하지 않으면 영영
-- 처리되지 않는데, 그 대상을 볼 화면이 없었다.
--
-- 이 화면의 일은 "FAILED 를 둘로 가르는 것"이다: 곧 자동으로 다시 시도될 건과, 아무도 다시
-- 시도하지 않는 건. 한 덩어리로 보면 운영자가 그 구분을 할 수 없다.
--
-- 화면 URL 이 /admin/refunds 가 아닌 이유: 그 URL 은 order-service 의 API 이고 같은 날
-- 게이트웨이로 노출됐다(V 없음 — gateway application.yml). 화면이 같은 URL 을 쓰면 새로고침
-- 때 API 응답이 렌더된다. spa-fallback-gate 가 이 오답을 막는다.
--
-- ADMIN,MANAGER: 서버가 /admin/refunds/** 를 그 등급으로 막는다.
--
-- 정렬은 '회수 채권' 뒤 — 환불·차지백·회수 채권 셋 다 "나간 돈을 되돌리는" 축이다.
-- 기준 형제는 서브쿼리가 아니라 조인으로 가져온다(서브쿼리면 경로 리터럴이 SELECT 절에 남아
-- menu-route-gate 의 시드 추출기가 '새로 심는 메뉴'로 센다).
-- ============================================================

UPDATE menus
   SET sort_order = sort_order + 1, updated_at = NOW()
 WHERE parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL)
   AND sort_order > (SELECT sort_order FROM menus WHERE path = '/admin/settlement/recoveries');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '환불 운영', '/admin/settlement/refunds', '↩️',
       '재시도 소진 건 · 결제별 환불 이력', 'BACKOFFICE', 'ITEM',
       r.sort_order + 1, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
CROSS JOIN menus r
WHERE p.name = '정산운영'
  AND p.parent_id IS NULL
  AND r.path = '/admin/settlement/recoveries'
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/settlement/refunds');
