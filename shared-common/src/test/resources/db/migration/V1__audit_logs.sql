-- shared-common 테스트 전용 스키마.
-- 운영의 V34__audit_logs.sql 은 order-service 모듈에 있어 이 모듈 클래스패스에 없다.
-- AuditLogPersistenceAdapterIT 가 매핑을 검증할 수 있도록 동일한 audit_logs 테이블을
-- 테스트 컨테이너의 기본 스키마(public)에 생성한다 (엔티티는 스키마 미지정 매핑).

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
