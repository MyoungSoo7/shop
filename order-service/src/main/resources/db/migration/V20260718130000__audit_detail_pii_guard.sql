-- V20260718130000: audit_logs.detail_json 주민등록번호 패턴 유입 거부 트리거 (R5 리뷰 후속, order)
--
-- [지적 · A-low] detail_json 의 PII 마스킹이 기록기 계약(COMMENT V20260718100000)에만 의존 —
--   append-only 라 계약 위반 PII 가 유입되면 사후 정정이 불가하다. 가장 형식이 확정적인
--   주민등록번호 패턴(6자리-성별코드 7자리)만 DB 레벨에서 fail-loud 로 거부해 계약 위반을
--   유입 시점에 드러낸다.
-- [범위 제한 사유] 계좌번호·전화번호는 형식이 느슨해 금액·ID 문자열과 오탐 충돌 — 정규식 거부
--   대상에서 제외하고 기록기 마스킹 계약에 위임한다(과차단이 감사 유실보다 위험).
-- [파티션] BEFORE INSERT row 트리거는 파티션드 부모에 걸면 전 파티션(기존+ensure 신규)에 적용된다.

CREATE OR REPLACE FUNCTION audit_detail_reject_rrn()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.detail_json IS NOT NULL
       AND NEW.detail_json::text ~ '\d{6}-[1-4]\d{6}' THEN
        RAISE EXCEPTION 'audit_logs.detail_json 에 주민등록번호 패턴 유입 — 기록기 마스킹 계약 위반(유입 전 마스킹 필수)';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_detail_rrn_guard ON opslab.audit_logs;
CREATE TRIGGER trg_audit_detail_rrn_guard
    BEFORE INSERT ON opslab.audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_detail_reject_rrn();
