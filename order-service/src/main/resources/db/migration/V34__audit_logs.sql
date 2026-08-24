-- V34: T2-⑤ Audit Log 테이블.
--
-- 민감 작업(정산 확정·환불·권한 변경·로그인 실패 등) 의 감사 추적.
-- 금액 관련 작업 후 누가 언제 무엇을 바꿨는지 포스트모템에서 조회 가능해야 한다.

CREATE TABLE IF NOT EXISTS opslab.audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    actor_id        BIGINT,                    -- 로그인 유저 id (시스템 액션이면 NULL)
    actor_email     VARCHAR(255),
    action          VARCHAR(50) NOT NULL,      -- AuditAction enum
    resource_type   VARCHAR(50),               -- Settlement / Payment / Refund / User
    resource_id     VARCHAR(64),               -- 문자열로 보관 (PK가 UUID 가 될 수도 있어)
    detail_json     JSONB,                     -- 작업 상세 (변경 전/후 값 등)
    ip_address      VARCHAR(45),               -- IPv4/IPv6
    user_agent      VARCHAR(500),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 조회 패턴별 인덱스
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_time
    ON opslab.audit_logs (actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_resource
    ON opslab.audit_logs (resource_type, resource_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action_time
    ON opslab.audit_logs (action, created_at DESC);

COMMENT ON TABLE opslab.audit_logs IS '민감 작업 감사 추적. 운영 장기 보관 권장 (월별 파티셔닝은 향후 과제).';
