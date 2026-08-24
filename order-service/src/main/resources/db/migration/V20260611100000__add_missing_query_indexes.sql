-- V20260611100000: 풀스캔 방지용 누락 인덱스 보강
--
-- 리포지토리 쿼리 메서드 ↔ 기존 인덱스를 전수 대조해, 실제 쿼리가 존재하는데
-- 인덱스가 없어 Seq Scan 또는 불필요한 정렬(Sort)이 발생하는 4개 지점만 보강한다.
-- (대부분의 테이블은 V2/V3/V22/V23 및 각 CREATE TABLE 에서 이미 충분히 인덱싱됨)
--
-- 과도한 인덱스는 쓰기 비용을 늘리므로, 근거(쿼리 메서드)가 명확한 것만 추가.

-- 1) pg_reconciliation_runs.findRecent: "ORDER BY started_at DESC LIMIT n" (필터 없음)
--    → started_at 인덱스가 없어 매 호출마다 전체 스캔 + Top-N 정렬 발생.
CREATE INDEX IF NOT EXISTS idx_pg_recon_runs_started
    ON opslab.pg_reconciliation_runs (started_at DESC);

-- 2) users.findByMembershipStatusOrderByCreatedAtAsc(status)
--    → 기존 idx_users_membership_pending 는 WHERE membership_status='PENDING' 부분 인덱스라
--      SUSPENDED/REJECTED 등 다른 상태 조회 시 사용 불가 → users 풀스캔.
--    APPROVED 는 대다수 행이라 어차피 Seq Scan 이지만, 선택도 높은 상태(SUSPENDED/REJECTED)와
--    created_at 정렬을 동시에 만족시키는 복합 인덱스를 추가.
CREATE INDEX IF NOT EXISTS idx_users_membership_created
    ON opslab.users (membership_status, created_at);

-- 3) payouts.sumCompletedBetween: "status='COMPLETED' AND completed_at BETWEEN ?" (seller 무관 집계)
--    → 기존 idx_payouts_seller_date 는 (seller_id, completed_at) 로 seller_id 가 선행이라
--      seller 무관 기간 집계에는 못 씀 → COMPLETED 전 구간 스캔.
--    COMPLETED 행만 대상으로 하는 부분 인덱스로 월별 정산 합계 쿼리를 인덱스 스캔으로 전환.
CREATE INDEX IF NOT EXISTS idx_payouts_completed_at
    ON opslab.payouts (completed_at)
    WHERE status = 'COMPLETED';

-- 4) reviews.findByProductIdOrderByCreatedAtDesc(productId)
--    → idx_reviews_product_id (product_id) 만 있어, 리뷰 많은 인기 상품 조회 시
--      인덱스로 행은 찾지만 created_at 정렬을 메모리에서 수행.
--    (product_id, created_at DESC) 복합으로 정렬 비용 제거(Index-ordered scan).
CREATE INDEX IF NOT EXISTS idx_reviews_product_created
    ON opslab.reviews (product_id, created_at DESC);
