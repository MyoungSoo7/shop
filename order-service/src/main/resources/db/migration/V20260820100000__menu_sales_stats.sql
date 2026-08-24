-- ============================================================
-- V20260820100000 : 정산운영 그룹에 '매출 통계' 화면 등록
--
-- 정산 콘솔은 "장부가 맞는가"를 보는 화면(정합성·대사·원장)으로 채워져 있었고,
-- "얼마나 팔렸는가"를 보는 화면이 없었다. 이 행이 그 진입점을 만든다.
--
-- ADMIN·MANAGER 둘 다 연다 — 서버가 /api/reports/** 를 같은 등급으로 막고 있고,
-- 이 화면은 읽기 전용 집계라 실행 권한과 분리할 이유가 없다.
--
-- 자리는 정합성 검증(0) 바로 다음 = 1. 현황(매출)을 먼저 보고 검증으로 넘어가는 순서다.
--
-- ⚠️ 특정 항목의 sort_order 를 개별로 바꾸지 않는다. 이 그룹은 0..9 가 이미 빈틈없이
--    차 있어서, 한 항목만 옮기면 그 자리에 있던 항목과 충돌해 순서가 뒤섞인다
--    (첫 시도에서 '일일 대사'를 3 으로 올렸다가 '차지백'과 겹쳤다). 1 이상을 통째로
--    한 칸 미는 편이 기존 상대 순서를 그대로 보존한다.
-- ============================================================

UPDATE menus
   SET sort_order = sort_order + 1
 WHERE parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL)
   AND sort_order >= 1
   AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/settlement/sales-stats');

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '매출 통계', '/admin/settlement/sales-stats', '📊',
       '기간 매출 · 전기 대비 · 결제수단/셀러/상품 구성', 'BACKOFFICE', 'ITEM', 1, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/settlement/sales-stats');
