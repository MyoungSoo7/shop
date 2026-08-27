-- ============================================================
-- V20260828140000 : 배송지 주소록 화면 메뉴 등록
--
--   · 배송지 주소록   /my/addresses   — 자주 쓰는 배송지를 저장해 두고 고른다 (SHOP)
--
-- 관리자 표면은 없다. 주소록은 회원이 자기 것만 보고 고치는 개인 자료이고, 운영자가 남의
-- 주소록을 훑을 이유가 생기면 그건 기능이 아니라 감사(auditconsole)의 일이다.
--
-- 화면 URL 이 /addresses 가 아니라 /my/addresses 인 것은 '내 문의'(/my/inquiries)와 같은
-- 이유다 — 짧은 명사 복수형은 게이트웨이로 프록시되는 API 세그먼트와 겹칠 위험이 있고,
-- 겹치면 새로고침에서 화면 대신 JSON 이 렌더된다. 접두사 /my 는 프록시 규칙에 없다.
--
-- 서버 API 는 /users/{userId}/shipping-addresses 다. 화면 경로와 일부러 다르게 뒀다 —
-- 소유자를 경로에 담는 쪽은 API 이고, 화면은 토큰의 주인 것만 보므로 담을 것이 없다.
--
-- sort_order 는 50 이다. SHOP 최상위가 7·8·20·25·30·35·40·45 로 차 있어 맨 뒤가 유일하게
-- 비어 있는 자리다. 뒤를 미는 UPDATE 는 두지 않는다(끼어들지 않으므로 필요 없다).
--
-- 0828 대 앞 번호(090000~130000)는 이미 차 있다. Flyway 는 버전이 겹치면 부팅에서 통째로
-- 죽는데, 병렬 세션이 올린 파일은 이름이 달라 git 이 충돌로 보지 않는다.
-- ============================================================

INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT NULL, '배송지 주소록', '/my/addresses', '📒',
       '자주 쓰는 배송지를 저장해 두고 고르기', 'SHOP', 'ITEM', 50, 'USER', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/my/addresses');
