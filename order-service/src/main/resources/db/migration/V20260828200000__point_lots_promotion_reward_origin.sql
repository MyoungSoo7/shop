-- 이벤트 프로모션 보상을 로트 출처에 허용한다.
--
-- marketing-service 를 분리하면서 PointLotOrigin 에 PROMOTION_REWARD 를 추가하고 컨슈머까지
-- 붙였는데, 이 CHECK 제약을 같이 넓히지 않았다. 그래서 출석 보상·럭키박스 당첨이 원장에 닿는
-- 순간 Postgres 가 insert 를 거절한다 — 트랜잭션이 롤백되니 ack 도 안 되고, 재전달을 반복하다
-- DLQ 로 간다. 즉 보상 기능 전체가 첫 한 건도 못 넣고 죽는다.
--
-- 이게 조용했던 이유: 컨슈머는 목으로만 시험됐고(테스트 0건이었다), 실 DB 를 태우는 경로에
-- PROMOTION_REWARD 로트를 만드는 테스트가 없었다. 애플리케이션 층에서는 어디도 틀린 데가 없다 —
-- 열거값도 있고 매핑도 맞다. 값 목록을 두 곳(자바 enum · DB CHECK)에 적어 두고 한쪽만 고치면
-- 컴파일러도 단위 테스트도 알려 주지 않는다. MarketingRewardRoundTripIT 가 이걸 잡았다.
--
-- 제약 교체는 테이블 전체를 한 번 훑는다(NOT VALID 로 두면 기존 행 검사를 미룰 수 있지만,
-- 넓히기만 하는 변경이라 기존 행은 전부 통과한다 — 굳이 미룰 이유가 없다).
ALTER TABLE point_lots DROP CONSTRAINT chk_point_lots_origin;
ALTER TABLE point_lots ADD CONSTRAINT chk_point_lots_origin
    CHECK (origin IN ('CHARGE_PRINCIPAL', 'CHARGE_BONUS', 'ORDER_EARN', 'MANUAL_GRANT',
                      'PROMOTION_REWARD', 'REFUND_RESTORE', 'TRANSFER_IN'));

COMMENT ON COLUMN point_lots.origin IS
    '로트 출처. GL 상대계정을 가른다(현금 충전분=부채 인식, 그 외 판촉성=판촉비 인식). PROMOTION_REWARD 는 marketing-service 가 이벤트로 요청한 보상 — 수기 지급과 나눈 이유는 회계가 아니라 캠페인 추적이다. PointLotOrigin 열거형과 이 CHECK 는 함께 움직여야 한다.';
