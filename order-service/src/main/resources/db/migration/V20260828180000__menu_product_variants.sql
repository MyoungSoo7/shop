-- ============================================================
-- V20260828180000 : 시스템 관리 그룹에 '상품 옵션' 등록
--
--   · 상품 옵션  /admin/system/product-variants  — 상품별 SKU 재고 · 추가금 · 조합 해석
--
-- '옵션 카탈로그'(/admin/system/option-catalog)와 짝처럼 보이지만 다른 표면이다. 그쪽은
-- "색상에는 빨강·파랑이 있다"는 *사전*이고, 이쪽은 "이 상품의 빨강/L 은 재고 3개, 추가금
-- 2,000원"이라는 *실물*이다. 사전만 있고 실물을 만들 화면이 없어서, SKU 를 만드는 길이
-- curl 뿐이었고 재고가 어긋나면 DB 를 직접 고쳤다.
--
-- 자리·URL·권한의 근거는 V20260827210000(동의 이력)과 같다. 요약하면:
--   ① sort_order 는 그룹 맨 뒤로 잡는다 — '옵션 카탈로그' 옆이 어울리지만 그 자리는 차 있다.
--   ② 화면 URL(/admin/system/product-variants)은 API 경로와 달라야 한다. 여기서는 특히
--      멀다 — API 가 /products/{id}/variants 라 /admin 아래가 아니다.
--   ③ required_role 은 ADMIN 이다. SecurityConfig 가 POST /products/*/variants 와
--      .../decrease-stock 을 ADMIN 으로 막는다. 재고 차감은 주문 없이 재고를 줄이는 조작이고
--      되돌리는 API 가 없어, 조회 콘솔들과 달리 MANAGER 에게 열지 않는다.
--
-- 0828 대 앞 번호(090000~170000)는 이미 차 있다. Flyway 는 버전이 겹치면 부팅에서 통째로
-- 죽는데, 병렬 세션이 올린 파일은 이름이 달라 git 이 충돌로 보지 않는다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '상품 옵션', '/admin/system/product-variants', '🧩',
       '상품별 SKU 재고 · 추가금 · 조합 해석', 'SYSTEM', 'ITEM',
       (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/system/product-variants');
