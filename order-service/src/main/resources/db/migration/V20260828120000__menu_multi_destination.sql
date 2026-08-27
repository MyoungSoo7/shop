-- ============================================================
-- V20260828120000 : 여러 곳 배송 화면 메뉴 등록
--
--   · 여러 곳 배송   /order/multi-destination   — 한 번에 담고 여러 주소로 나눠 보낸다 (SHOP)
--
-- 관리자 표면은 없다. 이 경로가 만드는 것은 평범한 주문 N 건이라 배송 관리·운송장 등록·반품
-- 큐가 이미 그대로 처리한다. 묶음이라고 따로 볼 화면을 만들면 같은 주문을 두 곳에서 다루게 된다.
--
-- 화면 URL 이 /orders/… 가 아니라 /order/… 인 이유: nginx 두 벌이 orders 세그먼트를
-- 게이트웨이로 프록시한다(복수형). 그 접두사에 화면을 두면 새로고침에서 API JSON 이 그대로
-- 렌더된다 — /my/inquiries 가 /inquiries 를 피한 것과 같은 이유다. 단수 /order 아래에는
-- 이미 /order/bulk·/order/pay 가 살고 있으므로 그 형제로 둔다.
--
-- required_role 은 USER 다. 서버 쪽 매처는 /orders/** 하나이고 이 경로도 그 아래 POST 라
-- anyRequest().authenticated() 위에서 "누구의 주문인가"는 ResourceOwnership 이 정한다.
--
-- 이번엔 뒤 항목을 밀지 않는다. SHOP 최상위의 sort_order 는 7·8·20·25·30·35·40 으로
-- 차 있고 이 항목은 맨 뒤(45)라 끼어들 자리가 없다 — 앞선 반품·교환·문의 마이그레이션이
-- 뒤를 한 칸씩 밀어야 했던 것은 그것들이 중간에 들어갔기 때문이다. 미는 UPDATE 는 멱등하게
-- 쓰기 까다로우므로 필요 없을 때는 아예 두지 않는다.
--
-- 버전 0828 대의 앞 번호(090000·100000·110000)는 이미 차 있다. Flyway 는 버전이 겹치면
-- 스키마 비교 이전에 통째로 죽고, 병렬 세션이 올린 파일은 이름이 달라 git 이 충돌로 보지
-- 않는다 — 머지된 뒤 부팅에서야 드러난다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT NULL, '여러 곳 배송', '/order/multi-destination', '🚚',
       '한 번에 담고 여러 주소로 나눠 보내기', 'SHOP', 'ITEM', 45, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/order/multi-destination');
