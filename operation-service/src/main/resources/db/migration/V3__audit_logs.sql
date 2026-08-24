-- V3: 공용 감사 로그 테이블 (shared-common common.audit)
--
-- operation-service 는 루트 컴포넌트 스캔으로 shared-common 의 AuditLogJpaEntity(@Table audit_logs) 를
-- 포함하므로, 자체 DB 에도 동일 테이블이 필요하다 (ddl-auto=validate 정합).
-- loan-service V5 / order-service V34 와 동일 스키마.

CREATE TABLE IF NOT EXISTS audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    actor_id        BIGINT,
    actor_email     VARCHAR(255),
    action          VARCHAR(50) NOT NULL,
    resource_type   VARCHAR(50),
    resource_id     VARCHAR(64),
    detail_json     JSONB,
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(500),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
