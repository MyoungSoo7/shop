-- ============================================================
-- V20260828170000 : 카테고리 탐색 화면 메뉴 등록
--
--   · 카테고리 탐색   /browse   — 분류를 골라 그 안의 상품을 본다 (SHOP)
--
-- 카테고리 트리는 오래전부터 있었고 만드는 콘솔도 있었다('이커머스 카테고리',
-- /admin/system/ecommerce-categories). 없던 것은 그것을 *구매자가 보는 길*이다 —
-- 구매자가 상품에 닿는 경로는 검색창 하나였고, 이름을 정확히 떠올려야만 찾을 수 있었다.
-- 분류를 정성껏 짜 놓고 그 결과를 아무도 못 보던 상태다.
--
-- 화면 URL 이 /categories 가 아닌 이유는 '내 문의'(/my/inquiries)와 같다 — categories 는
-- nginx 두 벌이 게이트웨이로 프록시하는 API 세그먼트라, 같으면 새로고침에서 화면 대신
-- 카테고리 목록 JSON 이 렌더된다. 고른 분류는 경로가 아니라 쿼리(?category=슬러그)에 남는다.
--
-- 서버 API 는 /categories(공개 트리)와 /categories/{slug} 다. 관리 API(/admin/categories)와
-- 다른 표면이다 — 공개 쪽은 활성 분류만 내려주므로 아직 열지 않은 분류가 새지 않는다.
--
-- sort_order 는 60 이다. SHOP 최상위가 7·8·20·25·30·35·40·45·50·55 로 차 있어 맨 뒤가
-- 유일하게 비어 있는 자리다. 뒤를 미는 UPDATE 는 두지 않는다(끼어들지 않으므로 필요 없다).
--
-- 0828 대 앞 번호(090000~160000)는 이미 차 있다. Flyway 는 버전이 겹치면 부팅에서 통째로
-- 죽는데, 병렬 세션이 올린 파일은 이름이 달라 git 이 충돌로 보지 않는다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT NULL, '카테고리 탐색', '/browse', '🧭',
       '분류를 골라 그 안의 상품 둘러보기', 'SHOP', 'ITEM', 60, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/browse');
