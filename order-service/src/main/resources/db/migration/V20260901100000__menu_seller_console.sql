-- ============================================================
-- V20260901100000 : 셀러 콘솔 메뉴 등록 (SELLER 영역 첫 행) + 운영자 심사 화면
--
--   · 셀러 콘솔      /seller/products  — 묶음(GROUP)
--       · 상품 등록   /seller/products  — 신청서 작성 · 수정 · 심사 요청
--       · 주문 · 출고 /seller/orders    — 내 상품 주문 조회 · 송장 등록
--   · 시스템 관리 아래
--       · 상품 심사   /admin/system/product-submissions — 운영자 승인 · 반려
--
-- 네 화면이 부르는 API 는 seller-service(8104)가 서빙한다. 메뉴 행이 여기 있는 이유는
-- 앞선 marketing · partner 메뉴와 같다 — 메뉴 원장은 order-service 의 menus 한 벌뿐이다.
--
-- ── required_role 이 'USER' 인 것은 실수가 아니다 ──────────────────────────────
-- 파트너 콘솔과 같은 이유다. 이 저장소의 메뉴 역할 어휘는 ADMIN · MANAGER · USER 뿐이고
-- SELLER 라는 역할이 없다. 'SELLER' 를 적으면 서버는 그런 역할을 모르니 아무도 걸러지지
-- 않는데 원장에는 통제가 있는 것처럼 남는다 — 막지 않는 통제는 보호가 아니라 착각이다.
-- 진짜 차단은 한 곳에서만 한다: seller-service 가 토큰의 회원번호로 조직을 찾아 없으면
-- 403 NOT_A_SELLER_MEMBER, 조직은 맞는데 파는 쪽이 아니면 422 NOT_A_SELLER_ORG 를 준다.
-- 셀러 번호는 화면이 아예 모르므로 번호를 바꿔 남의 신청서를 여는 것이 성립하지 않는다.
--
-- ── area = 'SELLER' ──────────────────────────────────────────────────────────
-- SELLER 는 enum 에는 있는데 지금까지 행이 하나도 없던 영역이다. 상단 네비는 area 로
-- 거르지 않고 최상위 노드를 전부 그리므로 area 는 표시 필터가 아니라 분류다 — 파는 쪽
-- 화면이 CORP(사는 기업) · BACKOFFICE(운영자) 와 섞이지 않게 하는 것이 목적이다.
--
-- ── sort_order 75 ────────────────────────────────────────────────────────────
-- 최상위가 7·8·20·25·30·35·40·45·50·55·60·65·70(파트너 콘솔) 로 차 있어 맨 뒤가 빈
-- 자리다. 뒤를 미는 UPDATE 는 두지 않는다(끼어들지 않는다).
--
-- ── 심사 화면이 왜 셀러 콘솔 안이 아닌가 ──────────────────────────────────────
-- 이 화면의 대상은 "내 조직" 이 아니라 전체 신청서다. 셀러 콘솔 그룹에 넣으면 그룹의
-- required_role 이 'USER,ADMIN' 이 되어야 하는데, Menu.isAccessibleBy 는 정확 일치라서
-- 그러면 운영자에게 자기가 403 을 받는 링크(/seller/products)가 함께 보인다. 다른
-- 서비스의 운영자 화면(환불 운영 · 셀러 등급)도 같은 이유로 시스템 관리 아래에 있다.
--
-- ── 자식을 path 로 막지 않는 이유 ─────────────────────────────────────────────
-- 묶음과 첫 자식이 같은 경로('/seller/products')를 갖는다. '배송' 묶음과 파트너 콘솔이
-- 이미 같은 모양이다 — 묶음을 눌러도 어딘가로 가게 하려면 그렇게 된다. 재실행 가드를
-- path 로 잡으면 자식이 부모 때문에 영영 안 들어간다. 이름 + 부모로 잡는다.
--
-- 0828 까지의 번호는 이미 차 있다. Flyway 는 버전이 겹치면 부팅에서 통째로 죽는데,
-- 병렬 세션이 올린 파일은 이름이 달라 git 이 충돌로 보지 않는다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT NULL, '셀러 콘솔', '/seller/products', '🏪',
       '셀러 상품 등록 · 주문 출고', 'SELLER', 'GROUP', 75, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.name = '셀러 콘솔' AND m.parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '상품 등록', '/seller/products', '📦',
       '상품 등록 신청서 작성 · 수정 · 심사 요청', 'SELLER', 'ITEM', 1, 'USER', TRUE, TRUE
FROM menus p
WHERE p.name = '셀러 콘솔' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.name = '상품 등록' AND m.parent_id = p.id);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '주문 · 출고', '/seller/orders', '🚚',
       '내 상품 주문 조회 · 송장 등록', 'SELLER', 'ITEM', 2, 'USER', TRUE, TRUE
FROM menus p
WHERE p.name = '셀러 콘솔' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.name = '주문 · 출고' AND m.parent_id = p.id);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '상품 심사', '/admin/system/product-submissions', '🔍',
       '셀러 상품 등록 신청 승인 · 반려', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/product-submissions');
