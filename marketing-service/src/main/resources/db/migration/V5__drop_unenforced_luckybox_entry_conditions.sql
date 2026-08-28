-- V5: 아무도 읽지 않는 럭키박스 참여 조건 4개를 걷어낸다
--
-- member_joined_from(가입일) / amount_basis + min_order_amount(주문금액) /
-- shipping_status_required(배송상태) 는 V1 부터 컬럼으로 있었고 운영 API 로 값이 들어왔지만,
-- LuckyboxCampaign.assertDrawAllowed 는 이 넷을 한 번도 읽지 않았다. 즉 "10만원 이상 구매 +
-- 배송완료 회원만" 으로 설정해도 실제로는 전원이 참여할 수 있었다. 조건이 아예 없는 것보다
-- 나쁘다 — 있는 줄 알기 때문이다.
--
-- 강제하는 쪽으로 가지 않은 이유는 데이터가 없어서다. 이 저장소의 서비스 간 연계는 Kafka
-- 이벤트뿐인데(ADR 0024/0035), 토픽 카탈로그 22개 중
--   * 배송 단계를 알리는 토픽은 하나도 없다 — 만들 소스 자체가 없다
--   * lemuel.order.created / lemuel.payment.captured 는 있지만 실결제금액·주문금액 구분을
--     배송 단계별로 인정하려면 회원별 구매금액 읽기 모델을 새로 만들어야 한다
--   * lemuel.user.registered 는 있지만 컨슈머 가동 이후분만 쌓인다. 백필 없이 켜면 기존
--     회원이 전부 "가입일 미달"로 차단된다 — 지금보다 더 나쁘다
-- 셋 다 order-service 를 함께 바꿔야 하는 별도 작업이라 여기에 섞지 않았다. 되살리는 순서와
-- 선행 조건은 docs/plan/marketing-legacy-gap.md §2 ④ 에 적어 두었다.
--
-- 되돌리기는 이 파일의 역순 ADD COLUMN 하나면 된다(값은 어차피 아무도 안 읽었으므로 보존할
-- 의미가 없다). marketing-service 는 아직 k8s 매니페스트에 없어 이 컬럼들이 실려 있는
-- 운영 DB 도 없다.
--
-- V1 을 직접 고치지 않는 이유는 V4 와 같다 — 이미 V1 을 적용한 로컬 DB 의 체크섬이 어긋난다.

ALTER TABLE luckybox_campaigns
    DROP CONSTRAINT IF EXISTS luckybox_campaigns_basis_ck,
    DROP CONSTRAINT IF EXISTS luckybox_campaigns_shipping_ck;

ALTER TABLE luckybox_campaigns
    DROP COLUMN IF EXISTS member_joined_from,
    DROP COLUMN IF EXISTS amount_basis,
    DROP COLUMN IF EXISTS min_order_amount,
    DROP COLUMN IF EXISTS shipping_status_required;
