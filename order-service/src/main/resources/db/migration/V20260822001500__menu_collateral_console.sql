-- ============================================================
-- V20260822001500 : '담보 감시' 화면을 CEO 메뉴에 등록
--
-- 담보 재평가(마진콜 140%·청산 120%)와 실행(처분·대위변제)은 서비스·정책 상수·단위 테스트가
-- 모두 있었는데 어떤 어댑터도 호출하지 않아 런타임에서 도달할 수 없었다 — 담보 가치가 반토막
-- 나도 아무 일이 일어나지 않는 상태였다(역산 PRD §10-C). REST 어댑터가 그 구멍을 메웠지만
-- 부르는 화면이 없어 여전히 사람이 손댈 수 없었다.
--
-- CEO 그룹인 이유: 대출 표면이 이미 여기 있고('대출관리' = 선정산·기업대출), 권한 등급도 같다
-- (서버가 /loans/secured/{id}/collateral/** 를 ADMIN·MANAGER 로 막는다).
--
-- 정렬은 '대출관리' 바로 뒤 — 같은 서비스의 다른 상품군이고, 대출을 보러 온 자리에서 담보
-- 상태로 이어지는 순서다. 뒤 형제들은 한 칸씩 민다.
--
-- 기준 형제는 서브쿼리가 아니라 조인으로 가져온다 — 서브쿼리면 그 경로 리터럴이 SELECT 절에
-- 남아 menu-route-gate 의 시드 추출기가 '새로 심는 메뉴'로 센다(조건절 줄만 걸러내는 추출기다).
-- ============================================================

UPDATE menus
   SET sort_order = sort_order + 1, updated_at = NOW()
 WHERE parent_id = (SELECT id FROM menus WHERE name = 'CEO' AND parent_id IS NULL)
   AND sort_order > (SELECT sort_order FROM menus WHERE path = '/admin/ceo/loans');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '담보 감시', '/admin/ceo/collateral', '🏚️',
       '재평가 · 마진콜 · 처분 · 대위변제', 'CEO', 'ITEM',
       l.sort_order + 1, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
CROSS JOIN menus l
WHERE p.name = 'CEO'
  AND p.parent_id IS NULL
  AND l.path = '/admin/ceo/loans'
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/ceo/collateral');
