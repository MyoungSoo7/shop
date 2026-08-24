-- ============================================================
-- V20260813200000 : '정산운영' 최상위 그룹 신설 + 정합성 검증 화면 등록
--
-- 배경: settlement-service 의 운영 API(/admin/integrity·대사·차지백·회수·마감·세무 등)는
--       화면이 없어 MCP 도구와 curl 로만 볼 수 있었다. P0 로 이 표면을 화면화하면서
--       진입점을 담을 그룹을 만든다.
--
-- 기존 '정산' 그룹과 분리하는 이유: 그쪽은 상품·정산 생성·지급처럼 매일 만지는 작업 화면이고,
-- 이쪽은 대사·정합성처럼 "지금 어디가 아픈지" 판정하는 운영 화면이다. 한 사이드바에 14개를
-- 몰아넣는 것보다 축을 나누는 편이 찾기 쉽다.
-- ============================================================

-- ── 1) 최상위 정렬 재배치 — '정산' 바로 뒤(2번)에 자리를 낸다
UPDATE menus SET sort_order = 3 WHERE parent_id IS NULL AND name = '배송';
UPDATE menus SET sort_order = 4 WHERE parent_id IS NULL AND name = '승인';
UPDATE menus SET sort_order = 5 WHERE parent_id IS NULL AND name = 'AI 도우미';
UPDATE menus SET sort_order = 6 WHERE parent_id IS NULL AND name = 'CEO';
UPDATE menus SET sort_order = 7 WHERE parent_id IS NULL AND name = '시스템 관리';
UPDATE menus SET sort_order = 8 WHERE parent_id IS NULL AND name = '주문하기';
UPDATE menus SET sort_order = 9 WHERE parent_id IS NULL AND name = '추천받기';

-- ── 2) '정산운영' 그룹
-- path 는 그룹을 눌렀을 때 착지할 대표 화면(첫 항목)이다.
INSERT INTO menus (parent_id, name, short_name, path, icon, description, area, menu_type,
                   sort_order, required_role, required_permission, visible, active)
VALUES (NULL, '정산운영', NULL, '/admin/settlement/integrity', '🧪', 'Settlement Operations',
        'BACKOFFICE', 'GROUP', 2, 'ADMIN,MANAGER', NULL, TRUE, TRUE);

-- ── 3) 정합성 검증 화면
-- 서버가 /admin/integrity/** 를 ADMIN·MANAGER 로 게이트하므로 메뉴도 같은 등급으로 맞춘다
-- (더 넓게 열면 눌러도 403 인 죽은 링크가 된다).
INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, '정합성 검증', '/admin/settlement/integrity', '🧪',
       '원장·지급·홀드백·체류 8종', 'BACKOFFICE', 'ITEM', 0, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL;
