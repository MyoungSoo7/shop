-- V20260715154000: incident_timeline 불변(append-only) 보증 + 인시던트 아카이빙 인덱스 — 3인 DB 리뷰 지적 반영
--
-- 설계 근거:
--   (6) incident_timeline 은 인시던트 라이프사이클(OPENED/REFIRED/ACKNOWLEDGED/RESOLVED/…)의 감사성
--       타임라인이다. 그러나 UPDATE/DELETE 를 막는 DB 보증이 없어 사후 조작·삭제가 가능하다는 지적.
--       → append-only 트리거(BEFORE UPDATE/DELETE RAISE)로 불변성을 강제한다. 조회 인덱스는 V1 의
--         idx_timeline_incident (incident_id, created_at) 가 "인시던트별 시간순"을 이미 커버 → 신규 불필요.
--   (7) incidents.status CHECK 도메인 대조: V1 의 chk_incident_status =
--       ('OPEN','ACKNOWLEDGED','RESOLVED','FALSE_POSITIVE') 는 현행 IncidentStatus enum 과 정확히 일치
--       (드리프트 없음) → 신규 CHECK 생략. 대신 종결(RESOLVED/FALSE_POSITIVE) 인시던트의 나이 기반
--       아카이빙 스캔용 부분 인덱스(resolved_at)를 보강한다.
--
-- ※ operation 기본 스키마는 opslab — 미한정 DDL 은 opslab 에 생성된다(V1~V4 동일 관례).

-- (6) incident_timeline append-only 강제 (감사성 타임라인 불변)
CREATE OR REPLACE FUNCTION incident_timeline_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'incident_timeline is append-only (audit trail): % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_incident_timeline_append_only ON incident_timeline;
CREATE TRIGGER trg_incident_timeline_append_only
    BEFORE UPDATE OR DELETE ON incident_timeline
    FOR EACH ROW EXECUTE FUNCTION incident_timeline_forbid_mutation();

-- (7) 종결 인시던트 나이 기반 아카이빙/정리 스캔용 부분 인덱스
CREATE INDEX IF NOT EXISTS idx_incident_resolved_archival
    ON incidents (resolved_at)
    WHERE status IN ('RESOLVED', 'FALSE_POSITIVE');

COMMENT ON TABLE incident_timeline IS
    '인시던트 라이프사이클 감사 타임라인. append-only — UPDATE/DELETE 는 trg_incident_timeline_append_only 로 차단. 정정은 새 COMMENT 행 추가로.';
