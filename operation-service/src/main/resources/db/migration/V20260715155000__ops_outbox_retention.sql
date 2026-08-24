-- V20260715155000: outbox_events / processed_events 리텐션 함수 + 정리 인덱스 — 3인 DB 리뷰 지적 반영
--
-- 설계 근거:
--   outbox_events(PUBLISHED)·processed_events 는 무한 적재되면 폴러 스캔·인덱스 비대·볼륨 부담이
--   누적된다. 발행 완료/처리 완료 행을 보존기간 기준으로 정리하는 함수를 제공한다(스케줄러/운영 훅에서
--   주기 호출). 삭제 기준 컬럼에 맞춘 인덱스도 보강한다.
--     · prune_outbox_published(INTERVAL): status='PUBLISHED' AND published_at 초과분 삭제.
--       V2 의 idx_ops_outbox_status_created 는 부분 인덱스 WHERE status IN ('PENDING','FAILED') 라
--       PUBLISHED 정리를 못 탐 → published_at 부분 인덱스(WHERE status='PUBLISHED')를 신설한다.
--     · prune_processed_events(INTERVAL): processed_at 초과분 삭제.
--       V2 의 idx_ops_processed_events_processed_at (processed_at) 가 이미 있어 정리 스캔을 커버 → 재사용.
--
-- 시그니처는 레인 간 통일 규약(E4 확정): prune_*(p_retention INTERVAL DEFAULT ...) RETURNS BIGINT.
--   (audit_logs 의 ensure/prune 은 E3 표준 months_ahead int / retain_months int — 본 outbox/processed 만 INTERVAL.)
-- ※ operation 기본 스키마는 opslab — 미한정 DDL 은 opslab 에 생성된다(V1~V4 동일 관례).

-- PUBLISHED outbox 정리 스캔용 부분 인덱스 (기존 인덱스가 PENDING/FAILED 전용이라 미커버)
CREATE INDEX IF NOT EXISTS idx_ops_outbox_published_prune
    ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';

-- 발행 완료(PUBLISHED) outbox 행을 보존기간(p_retention) 초과분만 삭제. 반환=삭제 행 수.
CREATE OR REPLACE FUNCTION prune_outbox_published(p_retention INTERVAL DEFAULT INTERVAL '7 days') RETURNS BIGINT AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    IF p_retention < INTERVAL '0' THEN
        RAISE EXCEPTION 'p_retention must be >= 0 (got %)', p_retention;
    END IF;
    DELETE FROM outbox_events
    WHERE status = 'PUBLISHED'
      AND published_at IS NOT NULL
      AND published_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql;

-- 처리 완료 멱등 추적(processed_events) 을 보존기간(p_retention) 초과분만 삭제. 반환=삭제 행 수.
-- (재처리 방어가 필요한 기간을 넘어선 행만 정리 — p_retention 은 컨슈머 재수신 창보다 넉넉히.)
CREATE OR REPLACE FUNCTION prune_processed_events(p_retention INTERVAL DEFAULT INTERVAL '30 days') RETURNS BIGINT AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    IF p_retention < INTERVAL '0' THEN
        RAISE EXCEPTION 'p_retention must be >= 0 (got %)', p_retention;
    END IF;
    DELETE FROM processed_events
    WHERE processed_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql;
