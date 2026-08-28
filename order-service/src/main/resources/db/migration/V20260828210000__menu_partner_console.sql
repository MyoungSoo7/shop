-- ============================================================
-- V20260828210000 : 파트너 콘솔 메뉴 등록 (CORP 영역 첫 행)
--
--   · 파트너 콘솔    /partner          — 묶음(GROUP)
--       · 매출 대시보드 /partner        — 기간별 매출 · 일자별 추이 · 인기 상품
--       · 주문 내역     /partner/orders — 결제 건별 조회 · CSV 내려받기
--
-- 세 화면이 부르는 API 는 이 서비스가 아니라 partner-service(8100)가 서빙한다(ADR 0046).
-- 그래도 메뉴 행이 여기 있는 이유는 앞선 marketing 메뉴와 같다 — 메뉴 원장은 order-service 의
-- menus 한 벌뿐이다. 서비스마다 메뉴 테이블을 두면 네비를 누가 그리는지가 흐려진다.
--
-- ── required_role 이 'USER' 인 것은 실수가 아니다 ──────────────────────────────
-- 이 저장소의 메뉴 역할 어휘는 ADMIN · MANAGER · USER 세 개뿐이고 PARTNER 라는 역할이
-- 없다. 여기에 'PARTNER' 를 적으면 서버는 그런 역할을 모르니 실제로는 아무도 걸러지지
-- 않는데, 원장에는 통제가 있는 것처럼 남는다 — 나중에 이 줄을 읽는 사람은 입점사만
-- 들어올 수 있다고 믿는다. 막지 않는 통제는 보호가 아니라 그 착각이다.
--
-- 그래서 메뉴는 로그인까지만 걸고, 진짜 차단은 한 곳에서만 한다: partner-service 가
-- 토큰의 회원번호로 조직을 찾아 없으면 403 NOT_A_PARTNER 를 준다. 입점사가 아닌 계정이
-- 이 메뉴를 눌러도 남의 매출이 보이지 않고, 화면은 "이 계정은 입점 조직에 속해 있지
-- 않습니다" 를 그린다. 조직 번호는 화면이 아예 모르므로 번호를 바꿔 여는 것도 불가능하다.
--
-- ── area = 'CORP' ────────────────────────────────────────────────────────────
-- CORP 는 지금까지 행이 하나도 없던 영역이다. 상단 네비는 area 로 거르지 않고 최상위
-- 노드를 전부 그리므로 area 는 표시 필터가 아니라 분류다 — 뒤에 붙을 기업 고객 화면들이
-- BACKOFFICE(운영자) 와 섞이지 않게 하는 것이 목적이다.
--
-- ── sort_order 70 ────────────────────────────────────────────────────────────
-- 최상위가 7·8·20·25·30·35·40·45·50·55·60·65 로 차 있어 맨 뒤가 빈 자리다. 뒤를 미는
-- UPDATE 는 두지 않는다(끼어들지 않는다).
--
-- ── 자식 두 개를 path 로 막지 않는 이유 ───────────────────────────────────────
-- 묶음과 첫 자식이 같은 경로('/partner')를 갖는다. '배송' 묶음(/admin/shipping)이 이미
-- 같은 모양이다 — 묶음을 눌러도 어딘가로 가게 하려면 그렇게 된다. 그래서 재실행 가드를
-- path 로 잡으면 자식이 부모 때문에 영영 안 들어간다. 이름 + 부모로 잡는다.
--
-- 0828 앞 번호(090000~200000)는 이미 차 있다. Flyway 는 버전이 겹치면 부팅에서 통째로
-- 죽는데, 병렬 세션이 올린 파일은 이름이 달라 git 이 충돌로 보지 않는다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT NULL, '파트너 콘솔', '/partner', '🏢',
       '입점 기업 매출 · 주문 조회', 'CORP', 'GROUP', 70, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.name = '파트너 콘솔' AND m.parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '매출 대시보드', '/partner', '📈',
       '기간별 매출 · 일자별 추이 · 인기 상품', 'CORP', 'ITEM', 1, 'USER', TRUE, TRUE
FROM menus p
WHERE p.name = '파트너 콘솔' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.name = '매출 대시보드' AND m.parent_id = p.id);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '주문 내역', '/partner/orders', '🧾',
       '결제 건별 조회 · CSV 내려받기', 'CORP', 'ITEM', 2, 'USER', TRUE, TRUE
FROM menus p
WHERE p.name = '파트너 콘솔' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.name = '주문 내역' AND m.parent_id = p.id);
