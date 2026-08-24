-- ============================================================
-- V20260821103000 : 정산운영 그룹에 '셀러 등급' 화면 등록
--
-- 등급 하나가 수수료율(NORMAL 3.5% / VIP 2.5% / STRATEGIC 2.0%)·정산주기·홀드백을 동시에
-- 정하는데, 지금까지 바꿀 진입점이 DB 밖에 없었다. 백엔드 콘솔(/admin/seller-tiers/**, ADR 0031)은
-- 진작 있었지만 부르는 화면이 없어 사실상 쓸 수 없는 기능이었다.
--
-- '수수료율' 바로 다음에 둔다 — 등급(입력) → 요율(적용) 순으로 읽힌다. 자리를 고정 숫자로
-- 박지 않고 수수료율의 현재 sort_order 에서 파생하는 이유: 이 그룹의 순서는 그동안 여러
-- 마이그레이션이 각자 UPDATE 로 밀어 왔고, 지금 값을 여기 상수로 적으면 그 사이에 낀
-- 다른 마이그레이션 하나로 조용히 어긋난다.
--
-- required_role 이 ADMIN 단독인 이유: 서버가 /admin/seller-tiers/** 를 ADMIN 으로 막는다
-- (SecurityConfig — 등급은 정산 금액을 바꾸므로 조회 콘솔과 달리 MANAGER 에게 열지 않는다).
-- MANAGER 에게 메뉴만 보여 주면 눌러도 403 인 죽은 링크가 된다.
--
-- 화면 URL 이 /admin/settlement/** 아래인 이유(API 는 order-service 의 /admin/seller-tiers 인데도):
-- nginx SPA 폴백이 /admin 하위에서 (system|operation|ceo|settlement|login) 만 index.html 로
-- 내려보낸다. 다른 접두사를 쓰면 새로고침·직접진입이 404 가 된다.
-- ============================================================

-- 수수료율 다음 자리를 비운다. NOT EXISTS 를 함께 걸어 두 번 적용돼도 순서가 밀리지 않게 한다.
UPDATE menus
   SET sort_order = sort_order + 1
 WHERE parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL)
   AND sort_order > (SELECT sort_order FROM menus
                      WHERE path = '/admin/settlement/commission-rates'
                        AND parent_id = (SELECT id FROM menus
                                          WHERE name = '정산운영' AND parent_id IS NULL))
   AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/settlement/seller-tiers');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '셀러 등급', '/admin/settlement/seller-tiers', '🏅',
       '등급 재산정 · 관리자 지정 · 캐시 정합', 'BACKOFFICE', 'ITEM',
       -- 수수료율이 아직 없는 DB 라면 그룹의 맨 뒤에 붙인다(순서만 다를 뿐 메뉴는 생긴다).
       COALESCE(
         (SELECT c.sort_order + 1 FROM menus c
           WHERE c.path = '/admin/settlement/commission-rates' AND c.parent_id = p.id),
         (SELECT COALESCE(MAX(m.sort_order), 0) + 1 FROM menus m WHERE m.parent_id = p.id)),
       'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영'
  AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/settlement/seller-tiers');
