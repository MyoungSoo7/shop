-- batch_run_history: 실행을 누가 걸었는지 남긴다.
--
-- 이 표는 V3 이 만들고 인덱스까지 네 개 걸어 뒀지만 쓰는 코드가 한 줄도 없어 운영에서 0행이었다.
-- (V20260820110000 의 정리 마이그레이션은 "order 가 계속 쓰는 공유 테이블" 이라는 근거로 이 표의
--  삭제를 면제했는데, 그 근거가 사실이 아니었다.) 이제 스케줄러 10개가 실제로 적는다.
--
-- 원래 스키마에는 트리거 주체 칸이 없다. 그런데 원장이 답해야 하는 질문의 절반은
-- "이 날짜분을 사람이 다시 돌린 적이 있나" 다 — 그게 없으면 같은 대상일에 두 행이 있을 때
-- 재실행인지 중복 실행인지 구분할 방법이 없다. 그래서 칸을 하나 더 둔다.
--
-- 값의 모양: 'scheduler' | 'startup' | 'rerun:<actor>' | 'rerun-dry:<actor>'
--
-- 기존 행에는 NULL 이 들어간다. 운영 데이터가 0행이라 backfill 할 것이 없고, 설령 있더라도
-- '알 수 없음' 을 'scheduler' 로 적어 넣는 건 원장에 없는 사실을 지어내는 일이다.
ALTER TABLE batch_run_history
    ADD COLUMN IF NOT EXISTS triggered_by VARCHAR(100);

COMMENT ON COLUMN batch_run_history.triggered_by IS
    '실행 트리거: scheduler | startup | rerun:<actor> | rerun-dry:<actor>. NULL 은 이 칸이 생기기 전 행.';

-- 운영 화면의 기본 질의는 "이 배치의 최근 실행" 이다. batch_name 단일 인덱스(V3)로는
-- 정렬까지 못 받아 매번 정렬이 들어간다. 복합으로 덮는다.
CREATE INDEX IF NOT EXISTS idx_batch_history_name_started
    ON batch_run_history(batch_name, started_at DESC);
