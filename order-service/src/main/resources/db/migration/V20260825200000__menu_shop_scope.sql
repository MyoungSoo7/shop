-- ============================================================
-- V20260825200000 : 메뉴 트리를 shop 범위(커머스 + 운영)로 좁힌다
--
-- 이 저장소는 order-service(커머스 코어)와 operation-service(운영·게시판·알림·교육)만 담는다.
-- 정산·여신·투자·계정계·보험·외부데이터 화면은 이 저장소에 존재하지 않으므로, 그 화면을 가리키던
-- 메뉴 행을 걷어낸다. 남겨 두면 눌러도 아무 데도 가지 않는 죽은 링크가 되고, menu-route-gate 가
-- 그것을 정확히 그렇게 신고한다.
--
-- 세 화면은 지우지 않고 **옮긴다**. 셋 다 부르는 API 가 order-service 것이라 여기서도 살아 있다:
--   · 상품관리(/product)        — 정산 그룹 아래 있었으나 그 그룹이 사라지므로 최상위로 올린다
--   · 환불 운영(/admin/refunds) — 화면 URL 을 /admin/system/refunds 로
--   · 셀러 등급(/admin/seller-tiers) — 화면 URL 을 /admin/system/seller-tiers 로
-- 뒤 둘은 area 도 SYSTEM 으로 옮긴다 — 자식은 부모와 같은 영역이어야 한다는 불변식이 있다.
-- 뒤 둘의 화면 URL 이 API 경로와 다른 이유는 종전과 같다: 같은 URL 을 쓰면 nginx SPA 폴백이
-- 게이트웨이로 보내 버려 새로고침 때 API 응답이 렌더된다(spa-fallback-gate 가 막는 오답).
-- 옮기는 것을 DELETE + INSERT 로 표현하지 않는다 — 같은 메뉴에 새 id 를 주는 셈이라 참조가 끊긴다.
-- SET 절의 첫 컬럼은 반드시 path 여야 한다: menu-route-gate 의 이동 파서가 `SET path = '/new' … WHERE
-- path = '/old'` 형태만 이동으로 읽는다. 다른 컬럼이 앞에 오면 이동이 아니라 신규 메뉴로 잡힌다.
--
-- 삭제는 두 문장으로 나눈다. 자식(parent_id IS NOT NULL) 을 먼저 지우고 그룹을 지운다 —
-- menus.parent_id 가 menus(id) 를 참조하므로 순서를 뒤집으면 FK 위반이 된다.
-- ============================================================

-- ── ① 살릴 화면 3종을 먼저 옮긴다 (그룹을 지우기 전에) ──────────────────────

-- 상품관리: 최상위 ITEM 으로. 정렬은 대시보드(0) 바로 뒤 — 사라지는 '정산' 그룹이 쓰던 자리다.
UPDATE menus
   SET parent_id = NULL, sort_order = 1, updated_at = NOW()
 WHERE path = '/product';

-- 환불 운영 · 셀러 등급: 시스템 관리 그룹 아래로. 정렬은 그 그룹의 맨 뒤에 잇는다.
UPDATE menus
   SET path = '/admin/system/refunds',
       parent_id = (SELECT id FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL),
       area = 'SYSTEM',
       sort_order = (SELECT MAX(m.sort_order) + 1 FROM menus m
                      WHERE m.parent_id = (SELECT id FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL)),
       updated_at = NOW()
 WHERE path = '/admin/settlement/refunds';

UPDATE menus
   SET path = '/admin/system/seller-tiers',
       parent_id = (SELECT id FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL),
       area = 'SYSTEM',
       sort_order = (SELECT MAX(m.sort_order) + 1 FROM menus m
                      WHERE m.parent_id = (SELECT id FROM menus WHERE name = '시스템 관리' AND parent_id IS NULL)),
       updated_at = NOW()
 WHERE path = '/admin/settlement/seller-tiers';

-- ── ② 이 저장소에 화면이 없는 메뉴를 걷어낸다 ────────────────────────────────

DELETE FROM menus
 WHERE parent_id IS NOT NULL
   AND path IN (
       '/admin/settlement', '/settlement/search', '/admin/settlement/payouts',
       '/admin/settlement/integrity', '/admin/settlement/sales-stats',
       '/admin/settlement/reconciliation', '/admin/settlement/pg-reconciliation',
       '/admin/settlement/chargebacks', '/admin/settlement/recoveries',
       '/admin/settlement/deposits', '/admin/settlement/monthly-closing',
       '/admin/settlement/tax', '/admin/settlement/commission-rates',
       '/admin/settlement/dlq', '/admin/settlement/ledger',
       '/ai/chat',
       '/admin/system/proof-review', '/admin/system/insurance-disclosures',
       '/admin/system/insurance-sales',
       '/admin/ceo/insight', '/admin/ceo/economics', '/admin/ceo/financials',
       '/admin/ceo/companies', '/admin/ceo/workforce', '/admin/ceo/invest',
       '/admin/ceo/invest-recommend', '/admin/ceo/loans', '/admin/ceo/collateral',
       '/admin/ceo/loan-guide', '/admin/ceo/loan-process', '/admin/ceo/lender-guide',
       '/admin/ceo/fund-guide', '/admin/ceo/accounts', '/admin/ceo/banking',
       '/admin/ceo/cards'
   );

DELETE FROM menus
 WHERE parent_id IS NULL
   AND path IN (
       '/admin/settlement', '/settlement/search', '/admin/settlement/payouts',
       '/admin/settlement/integrity', '/admin/settlement/sales-stats',
       '/admin/settlement/reconciliation', '/admin/settlement/pg-reconciliation',
       '/admin/settlement/chargebacks', '/admin/settlement/recoveries',
       '/admin/settlement/deposits', '/admin/settlement/monthly-closing',
       '/admin/settlement/tax', '/admin/settlement/commission-rates',
       '/admin/settlement/dlq', '/admin/settlement/ledger',
       '/ai/chat',
       '/admin/system/proof-review', '/admin/system/insurance-disclosures',
       '/admin/system/insurance-sales',
       '/admin/ceo/insight', '/admin/ceo/economics', '/admin/ceo/financials',
       '/admin/ceo/companies', '/admin/ceo/workforce', '/admin/ceo/invest',
       '/admin/ceo/invest-recommend', '/admin/ceo/loans', '/admin/ceo/collateral',
       '/admin/ceo/loan-guide', '/admin/ceo/loan-process', '/admin/ceo/lender-guide',
       '/admin/ceo/fund-guide', '/admin/ceo/accounts', '/admin/ceo/banking',
       '/admin/ceo/cards'
   );
