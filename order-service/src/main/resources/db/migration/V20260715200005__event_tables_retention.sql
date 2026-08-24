-- V20260715200005: outbox_events / processed_events 리텐션 정리 함수
--
-- 배경(DB 설계 리뷰 지적):
--   outbox_events(PUBLISHED 누적)·processed_events(멱등 이력 누적)는 상한 없이 무한 증가한다.
--   정산 트래픽이 쌓이면 폴러 조회·인덱스가 비대해지고 디스크가 마른다. 주기적 정리가 필요하다.
--
-- 파티셔닝을 하지 않는 이유:
--   두 테이블의 존재 이유는 "전역 유니크 event_id 기반 멱등 방어" 다
--   (outbox: uq_outbox_event_id / processed: PK(consumer_group, event_id)).
--   시간 파티셔닝을 하면 유니크 제약에 파티션 키(시각)를 포함해야 하는데, 그러면 같은 event_id 가
--   서로 다른 시간 파티션에 중복 삽입될 수 있어 멱등 방어가 무너진다. 따라서 파티셔닝 대신
--   "안전 경과분만 DELETE" 하는 리텐션 함수로 처리한다(발행/처리 완료 + 보존기간 경과분만 삭제).
--
-- 시그니처(레인 간 통일 — E4 확정): 파라미터는 INTERVAL(보존기간), 반환은 BIGINT(삭제 행 수).
-- 운영: 스케줄러/크론이 SELECT opslab.prune_outbox_published(INTERVAL '30 days'); 등으로 호출.

-- 1) PUBLISHED outbox 정리용 인덱스 (published_at 경과분 스캔).
--    기존 idx_outbox_status_created 는 PENDING/FAILED 전용 partial 이라 PUBLISHED 정리에 못 쓴다.
CREATE INDEX IF NOT EXISTS idx_outbox_published_at
    ON opslab.outbox_events (published_at)
    WHERE status = 'PUBLISHED';
-- processed_events 는 idx_processed_events_processed_at(V29) 를 그대로 재사용한다.

-- 2) PUBLISHED 상태로 보존기간(retain) 초과 경과한 outbox 행 삭제.
CREATE OR REPLACE FUNCTION opslab.prune_outbox_published(retain INTERVAL)
RETURNS BIGINT AS $$
DECLARE
    deleted BIGINT;
BEGIN
    IF retain IS NULL OR retain < INTERVAL '0' THEN
        RAISE EXCEPTION 'retain must be a non-negative INTERVAL (got %)', retain;
    END IF;

    DELETE FROM opslab.outbox_events
     WHERE status = 'PUBLISHED'
       AND published_at IS NOT NULL
       AND published_at < NOW() - retain;

    GET DIAGNOSTICS deleted = ROW_COUNT;
    RETURN deleted;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION opslab.prune_outbox_published(INTERVAL) IS
    'PUBLISHED + retain 경과한 outbox_events 삭제. 반환=삭제 행 수(BIGINT). PENDING/FAILED 는 절대 삭제 안 함.';

-- 3) processed_at 이 보존기간(retain) 초과 경과한 멱등 이력 삭제.
--    주의: 정상 재처리 지연을 넘는 넉넉한 보존기간을 넘겨야 한다(너무 짧으면 재수신 시 이중 처리 위험).
CREATE OR REPLACE FUNCTION opslab.prune_processed_events(retain INTERVAL)
RETURNS BIGINT AS $$
DECLARE
    deleted BIGINT;
BEGIN
    IF retain IS NULL OR retain < INTERVAL '0' THEN
        RAISE EXCEPTION 'retain must be a non-negative INTERVAL (got %)', retain;
    END IF;

    DELETE FROM opslab.processed_events
     WHERE processed_at < NOW() - retain;

    GET DIAGNOSTICS deleted = ROW_COUNT;
    RETURN deleted;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION opslab.prune_processed_events(INTERVAL) IS
    'processed_at + retain 경과한 멱등 이력 삭제. 반환=삭제 행 수(BIGINT). retain 은 재수신 지연을 넘는 값으로.';
