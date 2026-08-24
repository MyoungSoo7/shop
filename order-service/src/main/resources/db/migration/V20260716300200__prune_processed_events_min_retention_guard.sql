-- V20260716300200: prune_processed_events 최소 보존 하한 가드(≥7일) — 크로스컷 DB 리뷰 F3 (order)
--
-- [설계 근거]
--   processed_events 는 (consumer_group, event_id) 멱등 방어선이다. Kafka 재전송 창(리밸런스·재처리) 안에
--   있는 event_id 를 성급히 지우면, 재도착한 동일 이벤트가 "처음 본 것"으로 오인돼 이중 처리(이중 기표·이중
--   정산)로 이어진다. 리텐션은 반드시 재전송 최대 지연보다 커야 하므로, 실수로 짧은 값(예 '1 hour')을 넘겨도
--   DB 가 거부하도록 하한 7일 가드를 추가한다. 삭제 로직·시그니처는 기존(V20260715200005)과 동일 —
--   기존 비음수 가드를 7일 하한으로 강화한 것뿐이다(7일 > 0 이므로 비음수 조건을 포함).

CREATE OR REPLACE FUNCTION opslab.prune_processed_events(retain INTERVAL)
RETURNS BIGINT AS $$
DECLARE
    deleted BIGINT;
BEGIN
    IF retain IS NULL OR retain < INTERVAL '7 days' THEN
        RAISE EXCEPTION 'retain 은 최소 7일 이상이어야 합니다(재전송 창 내 멱등키 조기 삭제 → 이중 처리 방지). got=%', retain;
    END IF;

    DELETE FROM opslab.processed_events
     WHERE processed_at < NOW() - retain;

    GET DIAGNOSTICS deleted = ROW_COUNT;
    RETURN deleted;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION opslab.prune_processed_events(INTERVAL) IS
    'processed_at + retain 경과한 멱등 이력 삭제. retain 하한 7일(재전송 창 내 멱등키 보호). 반환=삭제 행 수(BIGINT).';
