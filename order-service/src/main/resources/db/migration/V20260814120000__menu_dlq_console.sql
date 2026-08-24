-- ============================================================
-- V20260814120000 : 정산운영 그룹에 'DLQ 재처리' 화면 등록
--
-- ADMIN 전용이다 — 서버가 /admin/dlq/** 를 ADMIN 으로만 막는다. 재처리는 이벤트를 원본 토픽으로
-- 다시 흘려보내는 실행이라(정산 파이프라인 재구동) 조회 권한과 등급을 같이 두지 않는다.
--
-- 수수료율 다음, 원장·시산표 앞에 둔다: 정책(요율) → 유실 복구(DLQ) → 기록(원장) 순으로 읽힌다.
-- ============================================================

UPDATE menus SET sort_order = 9
WHERE path = '/admin/settlement/ledger'
  AND parent_id = (SELECT id FROM menus WHERE name = '정산운영' AND parent_id IS NULL);

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, 'DLQ 재처리', '/admin/settlement/dlq', '📮',
       '처리 실패 이벤트 확인 · 재발행', 'BACKOFFICE', 'ITEM', 8, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '정산운영' AND p.parent_id IS NULL;
