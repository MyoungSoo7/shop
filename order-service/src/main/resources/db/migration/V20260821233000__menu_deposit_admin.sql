-- ============================================================
-- V20260821233000 : '예치금 운영' 화면을 정산운영 메뉴에 등록
--
-- 예치금 원장의 수기 경로(입출금·선점·상계)와 부족분 해소가 백엔드에만 있고 진입점이 없었다.
-- 선점·상계는 자동 트리거인 card 이벤트에 sellerId 가 없어 대상 계좌를 특정할 수 없으므로
-- 지금은 수기 콘솔이 유일한 입력 경로다(SPEC §3.16).
--
-- 부족분(shortfall)은 더 심했다: 도메인 주석이 "해소 주체가 아직 없다"고 명시하고
-- resolve/writeOff 의 프로덕션 호출자가 0건이며 OPEN 건을 도는 스케줄러도 없다.
-- 즉 화면이 없는 동안 부족분은 기록만 되고 영원히 쌓였다.
--
-- ADMIN 전용: SecurityConfig 가 /admin/deposits/** 를 hasRole("ADMIN") 으로 잠근다.
-- MANAGER 에게 보여 주면 눌러도 403 으로 되돌려보내지는 죽은 링크가 된다.
--
-- 정렬은 '회수 채권' 뒤 — 둘 다 셀러에게서 재원을 끌어오는 축이고, 상계 부족분이 회수 채권과
-- 같은 종류의 미결 잔여물이다. 뒤 형제들의 sort_order 를 하드코딩하지 않고 한 칸씩 밀어
-- '회수 채권' 의 현재 값에서 파생한다(앞선 마이그레이션이 값을 바꿨어도 따라간다).
-- ============================================================

UPDATE menus
   SET sort_order = sort_order + 1, updated_at = NOW()
 WHERE parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL)
   AND sort_order > (SELECT sort_order FROM menus WHERE path = '/admin/settlement/recoveries');

-- 기준 형제('회수 채권')를 서브쿼리가 아니라 조인으로 가져온다. 서브쿼리로 쓰면 그 경로
-- 리터럴이 SELECT 절에 남아 menu-route-gate 의 시드 추출기가 '새로 심는 메뉴'로 센다
-- (조건절 줄만 걸러내는 추출기다). 조인으로 옮기면 조건절에만 등장한다.
INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '예치금 운영', '/admin/settlement/deposits', '🏧',
       '수기 입출금 · 선점 · 상계 · 부족분 해소', 'BACKOFFICE', 'ITEM',
       r.sort_order + 1, 'ADMIN', TRUE, TRUE
FROM menus p
CROSS JOIN menus r
WHERE p.name = '정산운영'
  AND p.parent_id IS NULL
  AND r.path = '/admin/settlement/recoveries'
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/settlement/deposits');
