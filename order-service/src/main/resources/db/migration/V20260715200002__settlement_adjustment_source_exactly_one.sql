-- V20260715200002: 정산 조정(settlement_adjustments) 출처 제약을 exactly-one 으로 강화
--
-- 배경(DB 설계 리뷰 지적):
--   V44 의 chk_adjustment_refund_xor_chargeback 는 NOT VALID 상태이며,
--   (refund_id, chargeback_id) 조합으로 다음 3가지를 모두 허용했다:
--     ① 둘 다 NULL   ② refund 만   ③ chargeback 만
--   ①(둘 다 NULL)은 "원인 없는 음수 조정" — 환불도 분쟁도 아닌 정체불명 정산 차감이며
--   원장/시산표에 설명되지 않는 구멍을 만든다. 정합성 스위트가 잡아내는 바로 그 케이스다.
--
-- 도메인 판단(둘 다 NULL 은 불필요):
--   모든 정산 조정(음수)은 반드시 그 원인 — 환불(refund) 또는 카드사 분쟁(chargeback) — 하나에
--   1:1 로 귀속되어야 한다. V4/V25 가 둘 다 NULL 을 잠정 허용한 이유는 "Refund 엔티티 도입 전
--   감사 레코드 보존"이라는 임시 사유였고, 현재 Refund 엔티티가 존재하므로 그 근거는 소멸했다.
--   → NOT VALID 3분기 제약을 DROP 하고, exactly-one (정확히 하나) 로 교체한다.
--
-- 데이터 안전성:
--   opslab.settlement_adjustments 는 이 마이그레이션 시점까지 시드 INSERT 가 없고(V4·V17·V21 확인),
--   order-service 에 조정 생성 코드도 없다(조정 생성 경로는 정산 서비스 = 별도 settlement_db 소유).
--   즉 opslab 측 본 테이블은 비어 있어 위반 행이 존재하지 않는다 → 즉시 검증(VALIDATE) 이 안전하다.
--   만에 하나 둘 다 NULL 인 행이 있다면 아래 ADD 가 실패하며 배포 시점에 즉시 드러난다(무단 orphan 은
--   조용히 통과시키는 것보다 실패시키는 편이 회계상 옳다).

ALTER TABLE opslab.settlement_adjustments
    DROP CONSTRAINT IF EXISTS chk_adjustment_refund_xor_chargeback;

ALTER TABLE opslab.settlement_adjustments
    ADD CONSTRAINT chk_adjustment_source_exactly_one
        CHECK (num_nonnulls(refund_id, chargeback_id) = 1);

COMMENT ON CONSTRAINT chk_adjustment_source_exactly_one ON opslab.settlement_adjustments IS
    '정산 조정은 refund_id 또는 chargeback_id 중 정확히 하나에만 귀속(원인 없는 음수 조정 금지). V44 의 both-null 허용 제거.';
