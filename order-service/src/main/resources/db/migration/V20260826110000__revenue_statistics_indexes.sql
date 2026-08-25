-- 기간 매출 집계용 인덱스.
--
-- 수납 쪽은 이미 V23 의 idx_payments_captured_status_amount(captured_at, status) INCLUDE(amount, ...)
-- 가 그대로 쓰인다 — 커버링이라 힙을 안 탄다. 여기서 채우는 것은 환불 쪽이다.
--
-- refunds 에는 requested_at 인덱스만 있다(V4). 그런데 매출 차감은 *신청*이 아니라 *완료* 시각에
-- 달아야 한다 — 신청만 하고 실패한 환불까지 매출을 깎으면 있지도 않은 환불이 숫자를 줄인다.
-- 그래서 조회 축이 completed_at 인데, 그 축에 인덱스가 없어 기간 조회가 전건 스캔이 된다.
-- 환불 건수는 결제 건수보다 훨씬 적어 지금은 빠르지만, 느려지는 시점은 "환불이 쌓였을 때" 즉
-- 이 화면을 가장 자주 보게 되는 때다.
--
-- COMPLETED 만 담는 부분 인덱스로 만든다. REQUESTED·FAILED 는 이 통계가 절대 세지 않으므로
-- 인덱스에 실을 이유가 없고, 실패 환불은 재시도로 계속 갱신돼 인덱스만 부풀린다.
CREATE INDEX IF NOT EXISTS idx_refunds_completed_at
ON refunds (completed_at)
INCLUDE (amount)
WHERE status = 'COMPLETED';

-- 결제수단별 구성은 payment_tenders 를 payments 로 조인해 기간을 자른다. 조인 축(payment_id)은
-- V41 의 (payment_id, sequence) 로 이미 덮이지만, 집계가 읽는 tender_type·amount·status 가 없어
-- 매 행 힙을 탄다. 세 칸을 얹어 커버링으로 만든다.
CREATE INDEX IF NOT EXISTS idx_payment_tenders_payment_agg
ON payment_tenders (payment_id)
INCLUDE (tender_type, amount, status);
