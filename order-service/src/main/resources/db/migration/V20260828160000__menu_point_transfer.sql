-- ============================================================
-- V20260828160000 : 포인트 선물 화면 메뉴 등록
--
--   · 포인트 선물   /my/point-transfer   — 회원에게 내 포인트를 보낸다 (SHOP)
--
-- 관리자 표면은 없다. 운영자가 남의 포인트를 대신 옮길 수 있는 버튼은 만들지 않는다 —
-- 필요한 것은 이미 있는 수기 차감·수기 적립(AdminPointController)이고, 그쪽은 사유와
-- 담당자가 원장에 남는다. '대신 보내기'는 그 흔적 없이 같은 일을 할 수 있는 경로다.
--
-- 화면 URL 이 /point-transfer 가 아니라 /my/point-transfer 인 것은 '내 문의'(/my/inquiries),
-- '배송지 주소록'(/my/addresses)과 같은 이유다 — 짧은 명사형 경로는 게이트웨이로 프록시되는
-- API 세그먼트와 겹칠 위험이 있고, 겹치면 새로고침에서 화면 대신 JSON 이 렌더된다.
--
-- 서버 API 는 /api/points/transfers 다. 화면 경로와 다르게 뒀다 — 보내는 이는 경로가 아니라
-- 토큰에서 파생하므로 화면 쪽에 담을 식별자가 없다.
--
-- sort_order 는 55 다. SHOP 최상위가 7·8·20·25·30·35·40·45·50 으로 차 있어 맨 뒤가 유일하게
-- 비어 있는 자리다. 뒤를 미는 UPDATE 는 두지 않는다(끼어들지 않으므로 필요 없다).
--
-- 0828 대 앞 번호(090000~150000)는 이미 차 있다. Flyway 는 버전이 겹치면 부팅에서 통째로
-- 죽는데, 병렬 세션이 올린 파일은 이름이 달라 git 이 충돌로 보지 않는다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT NULL, '포인트 선물', '/my/point-transfer', '🎁',
       '내 포인트를 다른 회원에게 보내기', 'SHOP', 'ITEM', 55, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/my/point-transfer');
